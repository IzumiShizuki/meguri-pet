import { mkdtempSync, rmSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, expect, it, vi } from 'vitest'
import { createShizukiTimePrismClient } from './shizuki-timeprism.mjs'
import { createShizukiReminderRuntime } from './shizuki-reminders.mjs'
import { weeklyCron } from '../shared/recurring-todos.mjs'
const roots: string[] = []
afterEach(() => roots.splice(0).forEach(p => rmSync(p, { recursive: true, force: true })))
const response = (data: unknown, status=200) => new Response(JSON.stringify({data}), {status,headers:{'content-type':'application/json'}})
async function fixture(handler: (path: string, options: RequestInit) => Response) {
  const root = mkdtempSync(join(tmpdir(),'meguri-snapshot-')); roots.push(root)
  const client = createShizukiTimePrismClient({filePath:join(root,'auth.json'),clock:()=>Date.parse('2029-12-30T00:00:00Z'),
    safeStorage:{isEncryptionAvailable:()=>true,encryptString:(s:string)=>Buffer.from(s),decryptString:(b:Buffer)=>b.toString()},
    fetchImpl:async (url:string|URL|Request, options:RequestInit={})=>String(url).endsWith('/auth/tokens')
      ? response({result_type:'TOKEN_ISSUED',access_token:'fixture',refresh_token:'fixture',expires_in_sec:7200,user_id:42})
      : handler(new URL(String(url)).pathname,options)})
  await client.connect({origin:'https://site.shizuki.online',email:'fixture@example.invalid',password:'fixture-only'})
  return client
}
it('uses pure snapshot and never silently falls back to mutating legacy GET',async()=>{
  const calls:string[]=[]
  const client=await fixture((path,options)=>{calls.push(`${options.method} ${path}`);return response([],404)})
  await expect(client.listTodos()).rejects.toMatchObject({code:'snapshot_unsupported'})
  expect(calls).toEqual(['GET /api/v1/light-apps/todos/snapshot'])
})
it('uses POST sync; only explicit synchronization may fall back for an old server',async()=>{
  const calls:string[]=[]
  const client=await fixture((path,options)=>{calls.push(`${options.method} ${path}`);return response([],path.endsWith('/sync')?404:200)})
  expect(await client.syncTodos()).toEqual([])
  expect(calls).toEqual(['POST /api/v1/light-apps/todos/sync','GET /api/v1/light-apps/todos'])
})
it('scheduler chooses explicit synchronization when both operations exist',async()=>{
  const read=vi.fn(async()=>[]), sync=vi.fn(async()=>[])
  const root=mkdtempSync(join(tmpdir(),'meguri-sync-'));roots.push(root)
  const runtime=createShizukiReminderRuntime({filePath:join(root,'seen.json'),client:{status:()=>({connected:true,userId:42}),listTodos:read,syncTodos:sync},onReminder:()=>{}})
  try {runtime.start();await new Promise(resolve=>setImmediate(resolve));expect(sync).toHaveBeenCalledOnce();expect(read).not.toHaveBeenCalled()} finally {runtime.stop()}
})
const cases: { name: string, schedule: Record<string, any>, cronExpr: string }[]=JSON.parse(readFileSync(new URL('../../../../contracts/timeprism/recurring-fixtures.json',import.meta.url),'utf8'))
it.each(cases)('round-trips shared recurring schedule $name',async testCase=>{
  const saved:any[]=[]
  const client=await fixture((_path,options)=>{if(options.method==='POST'){saved.push({...JSON.parse(String(options.body)),rule_id:91});return response(saved[0])}return response(saved)})
  const s=testCase.schedule
  const actual=await client.saveRecurringRule({...s,enabled:true,reminderEnabled:true,expectedAccount:'https://site.shizuki.online:42',requestId:'00000000-0000-4000-8000-000000000042'})
  expect(weeklyCron(s.weekdays,s.time)).toBe(testCase.cronExpr)
  expect(actual).toMatchObject({title:s.title,cronExpr:testCase.cronExpr,timeZoneId:s.timeZoneId,startAt:s.startAt,endAt:s.endAt,deadlineRemindValue:s.deadlineRemindValue,deadlineRemindUnit:s.deadlineRemindUnit})
})
