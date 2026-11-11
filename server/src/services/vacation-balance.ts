export function annualVacationDays(year: number): number {
  if (year < 1) return 0;
  if (year <= 5) return 12 + (year - 1) * 2;
  return 22 + Math.floor((year - 6) / 5) * 2;
}
export function vacationBalance(hireDate: string | Date | null, taken: number, today: string) {
  const hire = hireDate instanceof Date ? hireDate.toISOString().slice(0,10) : String(hireDate || '').slice(0,10);
  let years = 0;
  if (/^\d{4}-\d{2}-\d{2}$/.test(hire) && hire <= today) {
    years = Number(today.slice(0,4)) - Number(hire.slice(0,4));
    if (today.slice(5) < hire.slice(5)) years--;
  }
  let accrued = 0;
  for (let year = 1; year <= years; year++) accrued += annualVacationDays(year);
  return { years, accrued, taken, available: accrued - taken, debt: Math.max(0, taken-accrued) };
}
