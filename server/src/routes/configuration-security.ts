import { Router } from 'express';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import pool from '../db/client';
import { UserAuthRequest, userAuthMiddleware, requirePasswordChangeComplete, requireRole } from '../middleware/user-auth';
const router=Router();
router.use('/companies/:companyId',userAuthMiddleware,requirePasswordChangeComplete,requireRole('admin','superadmin'),(req:UserAuthRequest,res,next)=>{
  if(req.user!.role!=='superadmin'&&!req.user!.companyIds.includes(req.params.companyId)){res.status(403).json({error:'Sin acceso a esta empresa'});return;}next();
});
router.get('/companies/:companyId/configuration-security',async(req:UserAuthRequest,res,next)=>{
 try{const row=(await pool.query('SELECT configuration_password_hash FROM companies WHERE id=$1',[req.params.companyId])).rows[0];res.json({configured:!!row?.configuration_password_hash});}catch(e){next(e);}
});
router.get('/superadmin/users/:userId/configuration-companies',userAuthMiddleware,requirePasswordChangeComplete,requireRole('superadmin'),async(req:UserAuthRequest,res,next)=>{
 try{const rows=await pool.query(`SELECT c.id,c.name FROM companies c JOIN user_companies uc ON uc.company_id=c.id
 JOIN users u ON u.id=uc.user_id WHERE uc.user_id=$1 AND u.role='admin' ORDER BY c.name`,[req.params.userId]);res.json({companies:rows.rows});}catch(e){next(e);}
});
router.post('/companies/:companyId/configuration-security/reset',requireRole('superadmin'),async(req:UserAuthRequest,res,next)=>{
 try{const result=await pool.query('UPDATE companies SET configuration_password_hash=NULL WHERE id=$1 RETURNING id,name',[req.params.companyId]);
 if(!result.rows.length){res.status(404).json({error:'Empresa no encontrada'});return;}
 for(const key of failures.keys())if(key.startsWith(req.params.companyId+':'))failures.delete(key);
 res.json({reset:true,company:result.rows[0],message:'Contraseña de configuración restablecida. En el próximo acceso se solicitará crear una nueva.'});
 }catch(e){next(e);}
});
const failures=new Map<string,{count:number;until:number}>();
router.post('/companies/:companyId/configuration-security',async(req:UserAuthRequest,res,next)=>{
 const key=req.params.companyId+':'+req.user!.id;const attempt=failures.get(key);
 if(attempt&&attempt.until>Date.now()&&attempt.count>=5){res.status(429).json({error:'Demasiados intentos. Espera 15 minutos.'});return;}
 const password=req.body?.password;
 if(typeof password!=='string'||password.length<8||password.length>128){res.status(400).json({error:'Usa entre 8 y 128 caracteres'});return;}
 try{
  let hash=(await pool.query('SELECT configuration_password_hash FROM companies WHERE id=$1',[req.params.companyId])).rows[0]?.configuration_password_hash;
  if(!hash){
   if(req.body?.confirm_password!==password){res.status(400).json({error:'Confirma la contraseña nueva'});return;}
   const created=await bcrypt.hash(password,12);
   const saved=await pool.query('UPDATE companies SET configuration_password_hash=$2 WHERE id=$1 AND configuration_password_hash IS NULL RETURNING configuration_password_hash',[req.params.companyId,created]);
   hash=saved.rows[0]?.configuration_password_hash;
   if(!hash){res.status(409).json({error:'La contraseña ya fue creada. Usa la contraseña existente.'});return;}
  }else if(!await bcrypt.compare(password,hash)){
   failures.set(key,{count:(attempt&&attempt.until>Date.now()?attempt.count:0)+1,until:Date.now()+900000});res.status(403).json({error:'Contraseña de configuración incorrecta'});return;
  }
  failures.delete(key);
  res.json({token:jwt.sign({purpose:'configuration',company:req.params.companyId,user:req.user!.id,fingerprint:hash.slice(-16)},process.env.JWT_SECRET!,{expiresIn:'15m'}),expires_in:900});
 }catch(e){next(e);}
});
router.get('/companies/:companyId/weekly-late-alerts',async(req:UserAuthRequest,res,next)=>{
 try{const rows=await pool.query(`SELECT e.id_remote AS employee_id,e.full_name, date_trunc('week',q.incident_date)::date::text AS week_start,
 COUNT(DISTINCT q.incident_date)::int AS late_days,FLOOR(COUNT(DISTINCT q.incident_date)/3.0)::int AS absences
 FROM qualified_incidents q JOIN employees e ON e.id_remote=q.employee_id
 WHERE q.company_id=$1 AND q.concept='Retardo'
 AND q.incident_date>=date_trunc('week',NOW() AT TIME ZONE 'America/Mexico_City')::date
 AND q.incident_date<date_trunc('week',NOW() AT TIME ZONE 'America/Mexico_City')::date+7
 GROUP BY e.id_remote,e.full_name,date_trunc('week',q.incident_date) HAVING COUNT(DISTINCT q.incident_date)>=3`,[req.params.companyId]);res.json(rows.rows);}catch(e){next(e);}
});
// Esta protección se ejecuta antes de los controladores de configuración existentes.
router.use('/companies/:companyId',async(req:UserAuthRequest,res,next)=>{
 const write=!['GET','HEAD','OPTIONS'].includes(req.method);
 const config=write&&/^\/(categories(?:\/|$)|policies(?:\/|$))/.test(req.path);
 const overtime=write&&req.body?.concept==='Tiempo extraordinario';
 if(!config&&!overtime){next();return;}
 try{
  const hash=(await pool.query('SELECT configuration_password_hash FROM companies WHERE id=$1',[req.params.companyId])).rows[0]?.configuration_password_hash;
  if(!hash){res.status(403).json({code:'CONFIGURATION_SETUP_REQUIRED',error:'Primero crea la contraseña de configuración'});return;}
  const token=req.get('X-Configuration-Token');
  const payload=token?jwt.verify(token,process.env.JWT_SECRET!):null;
  if(!payload||typeof payload==='string'||payload.purpose!=='configuration'||payload.company!==req.params.companyId||payload.user!==req.user!.id||payload.fingerprint!==hash.slice(-16))throw Error('invalid');
  next();
 }catch(e){res.status(403).json({code:'CONFIGURATION_LOCKED',error:'Introduce la contraseña de configuración para autorizar esta acción'});}
});
export default router;
