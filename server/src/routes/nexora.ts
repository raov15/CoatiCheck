import { Router, Response } from 'express';
import pool from '../db/client';
import { userAuthMiddleware, requirePasswordChangeComplete, requireRole, UserAuthRequest } from '../middleware/user-auth';
import { authMiddleware, AuthRequest } from '../middleware/auth';
import { vacationBalance } from '../services/vacation-balance';

const router = Router();
const access = (req: UserAuthRequest, res: Response) => {
  const allowed = req.user?.role === 'superadmin' || (req.user?.role === 'admin' && req.user.companyIds?.includes(req.params.companyId));
  if (!allowed) res.status(403).json({error:'Sin acceso a esta empresa'});
  return allowed;
};
router.use('/companies/:companyId',userAuthMiddleware, requirePasswordChangeComplete);
router.get('/companies/:companyId/positions', async (req: UserAuthRequest,res:Response):Promise<void>=>{
  if(!access(req,res)) return;
  try {
    const result=await pool.query('SELECT id,name FROM company_positions WHERE company_id=$1 AND is_active ORDER BY lower(name)',[req.params.companyId]);
    res.json(result.rows);
  } catch(error){res.status(500).json({error:'No se pudo cargar el catálogo de cargos'});}
});
router.post('/companies/:companyId/positions',requireRole('admin','superadmin'),async(req:UserAuthRequest,res:Response):Promise<void>=>{
  if(!access(req,res))return;
  const name=String(req.body?.name||'').trim();
  if(!name||name.length>120){res.status(400).json({error:'Escribe un cargo de hasta 120 caracteres'});return;}
  try{
    const r=await pool.query(`INSERT INTO company_positions(company_id,name) VALUES($1,$2)
      ON CONFLICT (company_id,(lower(name))) DO UPDATE SET is_active=TRUE,name=EXCLUDED.name RETURNING id,name`,[req.params.companyId,name]);
    res.status(201).json(r.rows[0]);
  }catch(error){res.status(409).json({error:'No se pudo guardar el cargo'});}
});
router.put('/companies/:companyId/positions/:id',requireRole('admin','superadmin'),async(req:UserAuthRequest,res:Response):Promise<void>=>{
  if(!access(req,res))return;
  const name=String(req.body?.name||'').trim();if(!name||name.length>120){res.status(400).json({error:'Cargo inválido'});return;}
  const client=await pool.connect();
  try{
    await client.query('BEGIN');
    const old=await client.query('SELECT name FROM company_positions WHERE id=$1 AND company_id=$2 AND is_active FOR UPDATE',[req.params.id,req.params.companyId]);
    if(!old.rows[0]){await client.query('ROLLBACK');res.status(404).json({error:'Cargo no encontrado'});return;}
    const result=await client.query('UPDATE company_positions SET name=$3 WHERE id=$1 AND company_id=$2 RETURNING id,name',[req.params.id,req.params.companyId,name]);
    await client.query('UPDATE employees SET department=$3,updated_at=NOW() WHERE company_id=$1 AND department=$2',[req.params.companyId,old.rows[0].name,name]);
    await client.query('COMMIT');res.json(result.rows[0]);
  }catch(error){await client.query('ROLLBACK');res.status(409).json({error:'Ya existe ese cargo o no se pudo editar'});}
  finally{client.release();}
});
router.delete('/companies/:companyId/positions/:id',requireRole('admin','superadmin'),async(req:UserAuthRequest,res:Response):Promise<void>=>{
  if(!access(req,res))return;
  const r=await pool.query('UPDATE company_positions SET is_active=FALSE WHERE id=$1 AND company_id=$2 RETURNING id',[req.params.id,req.params.companyId]);
  res.status(r.rowCount?200:404).json(r.rowCount?{deleted:true}:{error:'Cargo no encontrado'});
});

router.get('/companies/:companyId/employees/:employeeCode/vacations',async(req:UserAuthRequest,res:Response):Promise<void>=>{
  if(!access(req,res))return;
  try{
    const e=await pool.query(`SELECT id_remote,employee_code,full_name,hire_date,(NOW() AT TIME ZONE 'America/Mexico_City')::date::text AS today
      FROM employees WHERE company_id=$1 AND employee_code=$2 AND is_active`,[req.params.companyId,req.params.employeeCode]);
    if(!e.rows[0]){res.status(404).json({error:'Trabajador no encontrado'});return;}
    const employee=e.rows[0];
    const rows=await pool.query('SELECT * FROM employee_vacations WHERE company_id=$1 AND employee_id=$2 ORDER BY start_date DESC,created_at DESC',[req.params.companyId,employee.id_remote]);
    const taken=rows.rows.reduce((sum:number,r:any)=>sum+Number(r.days_taken),0);
    res.json({employee_code:employee.employee_code,employee_name:employee.full_name,hire_date:employee.hire_date,
      total_taken:taken,...vacationBalance(employee.hire_date,taken,employee.today),vacations:rows.rows});
  }catch(error){console.error('Error consultando saldo:',error);res.status(500).json({error:'No se pudo consultar el saldo de vacaciones'});}
});
router.post('/companies/:companyId/employees/:employeeCode/vacations',requireRole('admin','superadmin'),async(req:UserAuthRequest,res:Response):Promise<void>=>{
  if(!access(req,res))return;
  const {start_date:start,end_date:end,notes,confirm_advance:confirmed}=req.body||{};
  const days=Number(req.body?.days_taken);
  if(!Number.isInteger(days)||days<1||!/^\d{4}-\d{2}-\d{2}$/.test(start||'')||!/^\d{4}-\d{2}-\d{2}$/.test(end||'')||start>end||!Number.isFinite(Date.parse(start))||!Number.isFinite(Date.parse(end))){res.status(400).json({error:'Fechas y número entero de días válidos son obligatorios'});return;}
  const client=await pool.connect();
  try{
    await client.query('BEGIN');
    const employee=(await client.query(`SELECT id_remote,hire_date,(NOW() AT TIME ZONE 'America/Mexico_City')::date::text AS today
      FROM employees WHERE company_id=$1 AND employee_code=$2 AND is_active FOR UPDATE`,[req.params.companyId,req.params.employeeCode])).rows[0];
    if(!employee){await client.query('ROLLBACK');res.status(404).json({error:'Trabajador no encontrado'});return;}
    const existing=await client.query('SELECT * FROM employee_vacations WHERE company_id=$1 AND employee_id=$2',[req.params.companyId,employee.id_remote]);
    const taken=existing.rows.reduce((sum:number,r:any)=>sum+Number(r.days_taken),0);
    const balance=vacationBalance(employee.hire_date,taken,employee.today);
    const duplicate=existing.rows.find((r:any)=>String(r.start_date instanceof Date?r.start_date.toISOString():r.start_date).slice(0,10)===start&&String(r.end_date instanceof Date?r.end_date.toISOString():r.end_date).slice(0,10)===end&&Number(r.days_taken)===days);
    if(duplicate){await client.query('COMMIT');res.json({saved:true,vacation:duplicate,...balance});return;}
    if(days>balance.available&&confirmed!==true){await client.query('ROLLBACK');res.status(409).json({code:'CONFIRM_ADVANCE',available:balance.available,
      error:`Solo tienes ${balance.available} días disponibles. ¿Deseas tomar más días por adelantado?`});return;}
    const result=await client.query(`INSERT INTO employee_vacations(company_id,employee_id,start_date,end_date,days_taken,notes,advance_confirmed)
      VALUES($1,$2,$3::date,$4::date,$5,$6,$7) RETURNING *`,[req.params.companyId,employee.id_remote,start,end,days,typeof notes==='string'?notes.trim():null,days>balance.available]);
    await client.query('COMMIT');res.status(201).json({saved:true,vacation:result.rows[0],...vacationBalance(employee.hire_date,taken+days,employee.today)});
  }catch(error){await client.query('ROLLBACK');console.error('Error registrando vacaciones:',error);res.status(500).json({error:'No se pudieron guardar las vacaciones'});}
  finally{client.release();}
});
export const devicePositionsRouter=Router();
devicePositionsRouter.get('/',authMiddleware,async(req:AuthRequest,res:Response):Promise<void>=>{
  try{
    const rows=await pool.query(`SELECT p.name FROM company_positions p JOIN devices d ON d.company_id=p.company_id
      WHERE d.id_remote=$1 AND p.is_active ORDER BY lower(p.name)`,[req.deviceId]);res.json(rows.rows.map(r=>r.name));
  }catch(error){res.status(500).json({error:'No se pudieron descargar los cargos'});}
});
export default router;
