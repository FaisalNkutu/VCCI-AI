import 'dotenv/config';
import express from 'express';
import { Readable } from 'node:stream';

const app = express();
app.use(express.json({limit:'1mb'}));
const PORT = Number(process.env.PORT || 8787);
const PYTHON_SERVICE_URL = process.env.PYTHON_SERVICE_URL || 'http://127.0.0.1:9999';
const AI_CORE_REGISTRY_URL = process.env.AI_CORE_REGISTRY_URL || 'http://127.0.0.1:9801/api/ai-core/agents';
const PARADIGM_BRIDGE_TOKEN = process.env.PARADIGM_BRIDGE_TOKEN || '';
let paradigmSnapshot: {commandList: Record<string,string[]>;agentURLs:Record<string,string>} | null = null;
// Explicitly configured upstreams only; the browser cannot supply arbitrary target URLs.
const discoveredEndpoints = new Set<string>();
const allowed = new Set([PYTHON_SERVICE_URL,AI_CORE_REGISTRY_URL,...(process.env.AI_CORE_ENDPOINTS || '').split(',')].filter(Boolean).map(s=>s.replace(/\/+$/,'')));
function validate(urlText:string) {
 const u = new URL(urlText);
 if (!['http:','https:'].includes(u.protocol) || u.username || u.password || u.hash) throw new Error('Invalid upstream URL');
 if (!discoveredEndpoints.has(u.href) && ![...allowed].some(base => { const b=new URL(base); return u.origin===b.origin && (u.pathname===b.pathname || (base===PYTHON_SERVICE_URL && ['/','/agents','/router/select'].includes(u.pathname))); })) throw new Error('Upstream not allowlisted');
 // No user-supplied upstreams, preventing arbitrary proxy access.
 return u;
}
async function forward(url:string,method:'GET'|'POST',payload?:unknown,stream=false){
 const target=validate(url);
 const response=await fetch(target,{method,headers:{Accept:stream?'text/event-stream, application/json':'application/json',...(method==='POST'?{'Content-Type':'application/json'}:{}),...(stream?{'A2A-Version':'1.0'}:{})},body:method==='POST'?JSON.stringify(payload):undefined,signal:AbortSignal.timeout(180000)});
 return response;
}
async function relay(res:express.Response,upstream:Response){
 res.status(upstream.status);
 res.setHeader('Content-Type',upstream.headers.get('content-type') || 'application/json');
 res.setHeader('Cache-Control','no-cache, no-transform');
 res.setHeader('X-Content-Type-Options','nosniff');
 if(!upstream.body){res.end();return;}
 const stream=Readable.fromWeb(upstream.body as any);
 stream.on('error',()=>res.end());
 stream.pipe(res);
}
const fail=(res:express.Response,e:unknown)=>res.status(502).json({error:e instanceof Error?e.message:String(e)});
app.get('/api/health',(_req,res)=>res.json({status:'ready',pythonService:PYTHON_SERVICE_URL,aiCoreConfigured:Boolean(AI_CORE_REGISTRY_URL)}));
app.get('/api/agents',async(_req,res)=>{try{await relay(res,await forward(PYTHON_SERVICE_URL.replace(/\/+$/,'')+'/agents','GET'));}catch(e){fail(res,e);}});
app.post('/api/paradigm/discovery',(req,res)=>{
 if (!PARADIGM_BRIDGE_TOKEN || req.get('X-Paradigm-Bridge-Token') !== PARADIGM_BRIDGE_TOKEN) {res.status(403).json({error:'Paradigm bridge token required'});return;}
 const {commandList,agentURLs}=req.body||{};
 if (!commandList || typeof commandList!=='object' || Array.isArray(commandList) || !agentURLs || typeof agentURLs!=='object' || Array.isArray(agentURLs)) {res.status(400).json({error:'Expected commandList and agentURLs objects'});return;}
 const safeCommands:Record<string,string[]>={}; const safeURLs:Record<string,string>={};
 for(const [id,values] of Object.entries(commandList)){if(id && Array.isArray(values))safeCommands[id]=values.filter((v):v is string=>typeof v==='string');}
 for(const [id,url] of Object.entries(agentURLs)){if(id && typeof url==='string')safeURLs[id]=url;}
 paradigmSnapshot={commandList:safeCommands,agentURLs:safeURLs};
 res.json({ok:true,count:new Set([...Object.keys(safeCommands),...Object.keys(safeURLs)]).size});
});
app.get('/api/ai-core/agents',async(_req,res)=>{try{const upstream=await forward(AI_CORE_REGISTRY_URL,'GET');if(!upstream.ok)throw new Error(`AI Core registry HTTP ${upstream.status}`);await relay(res,upstream);}catch(e){if(paradigmSnapshot){res.json(paradigmSnapshot);return;}fail(res,e);}});
// Auto-selection uses the existing Python router; agent discovery and execution remain unchanged.
// Candidate metadata is assembled only from trusted server-side registries.
type RouteAgent = {id:string;name:string;type:'north'|'aicore';description:string;skills:Array<{name:string;description:string;tags:string[]}>};
app.post('/api/router/select',async(req,res)=>{try{
 const message=req.body?.message;
 if(typeof message!=='string'||!message.trim()||message.length>20000){res.status(400).json({error:'Invalid message'});return;}
 const [pythonResult,coreResult]=await Promise.allSettled([
  forward(PYTHON_SERVICE_URL.replace(/\/+$/,'')+'/agents','GET'),
  forward(AI_CORE_REGISTRY_URL,'GET')
 ]);
 const trusted:RouteAgent[]=[];
 if(pythonResult.status==='fulfilled'&&pythonResult.value.ok){
  const data:any=await pythonResult.value.json();
  for(const a of (Array.isArray(data?.agents)?data.agents:[])){
   if(typeof a?.id!=='string'||!a.id)continue;
   trusted.push({id:a.id,name:String(a.name||a.id),type:'north',description:String(a.description||''),skills:Array.isArray(a.skills)?a.skills.map((skill:any)=>({name:String(skill?.name||''),description:String(skill?.description||''),tags:Array.isArray(skill?.tags)?skill.tags.filter((tag:unknown):tag is string=>typeof tag==='string'):[]})):[]});
  }
 }
 let coreData:any=null;
 if(coreResult.status==='fulfilled'&&coreResult.value.ok)coreData=await coreResult.value.json();
 // If AI Core is unavailable, use the existing authenticated Paradigm snapshot.
 if(!coreData&&paradigmSnapshot)coreData=paradigmSnapshot;
 if(coreData){
  if(Array.isArray(coreData.agents)){
   for(const a of coreData.agents){
    if(!a?.id&&!a?.name)continue;
    const commands=Array.isArray(a.commands)?a.commands:[];
    trusted.push({id:String(a.id||a.name),name:String(a.name||a.id),type:'aicore',description:String(a.description||''),skills:commands.map((cmd:unknown)=>({name:String(cmd),description:String(cmd),tags:[]}))});
   }
  }else if(coreData.agents&&typeof coreData.agents==='object'){
   for(const [id,url] of Object.entries(coreData.agents))if(typeof url==='string')trusted.push({id,name:id,type:'aicore',description:'',skills:[]});
  }else{
   const commands=coreData.commandList||{};const urls=coreData.agentURLs||{};
   for(const id of new Set([...Object.keys(commands),...Object.keys(urls)])){
    const list=Array.isArray(commands[id])?commands[id]:[];
    trusted.push({id,name:id,type:'aicore',description:'',skills:list.map((cmd:unknown)=>({name:String(cmd),description:String(cmd),tags:[]}))});
   }
  }
 }
 // Honour the Workbench's selected candidates, but never accept caller-provided metadata.
 const requested=Array.isArray(req.body?.agents)?req.body.agents:[];
 const candidates=requested.length?trusted.filter(a=>requested.some((r:any)=>r?.id===a.id&&r?.type===a.type)):trusted;
 if(!candidates.length){res.json({agent:null,confidence:0,reason:'No discovered agents available for auto-selection'});return;}
 // This is the same message/agents contract already proven by the PowerShell test.
 const routerAgents=candidates.map(a=>({id:a.id,name:a.name,description:a.description,skills:a.skills}));
 const upstream=await forward(PYTHON_SERVICE_URL.replace(/\/+$/,'')+'/router/select','POST',{message,agents:routerAgents});
 const result:any=await upstream.json();
 if(!upstream.ok){res.status(upstream.status).json(result);return;}
 const selectedId=String(result?.agentId||result?.agent?.id||'');
 const selected=candidates.find(a=>a.id===selectedId);
 if(!selected){res.json({agent:null,confidence:0,reason:result?.reason||'Router did not select a registered agent',method:result?.method||'metadata'});return;}
 res.json({...result,agent:{...result.agent,id:selected.id,name:selected.name,type:selected.type},agentId:selected.id,agentName:selected.name});
 }catch(e){fail(res,e);}});
app.post('/api/a2a',async(req,res)=>{try{await relay(res,await forward(PYTHON_SERVICE_URL.replace(/\/+$/,'')+'/','POST',req.body,true));}catch(e){fail(res,e);}});
app.post('/api/ai-core/execute',async(req,res)=>{
 const {agentId,message,contextId,context}=req.body || {};
 if(typeof agentId!=='string'||typeof message!=='string'||typeof contextId!=='string'){res.status(400).json({error:'Invalid request'});return;}
 // Client does not control destination. Registry endpoints must be explicitly allowlisted.
 const map=Object.fromEntries((process.env.AI_CORE_AGENT_ENDPOINT_MAP||'').split(',').filter(Boolean).map(x=>{const i=x.indexOf('=');return [x.slice(0,i),x.slice(i+1)];}));
  try {
    // Resolve the agent from the server-side AI Core registry, not from a browser-supplied URL.
    // The optional environment map remains a fallback for older deployments.
    let endpoint: string | undefined;
    const registryResponse = await forward(AI_CORE_REGISTRY_URL, 'GET');
    if (registryResponse.ok) {
      const registry: any = await registryResponse.json();
      const agents = registry?.agents;
      if (agents && !Array.isArray(agents) && typeof agents === 'object') {
        const value = agents[agentId];
        if (typeof value === 'string') endpoint = value;
      } else if (Array.isArray(agents)) {
        const entry = agents.find((a: any) => a?.id === agentId || a?.name === agentId);
        if (entry) endpoint = entry.endpoint || entry.url || entry.agentUrl;
      }
      if (!endpoint && registry?.agentURLs && typeof registry.agentURLs[agentId] === 'string') {
        endpoint = registry.agentURLs[agentId];
      }
    }
    if (!endpoint && paradigmSnapshot) endpoint = paradigmSnapshot.agentURLs[agentId];
    if (!endpoint) endpoint = map[agentId];
    if (!endpoint) {res.status(404).json({error:`No registered endpoint for AI Core agent: ${agentId}`});return;}

    // Permit only exact URLs learned from the trusted server-side registry.
    // Reject non-loopback endpoints unless explicitly allowlisted through AI_CORE_ENDPOINTS.
    const parsed = new URL(endpoint);
    const loopback = ['127.0.0.1','localhost','::1','[::1]'].includes(parsed.hostname);
    if (!loopback && ![...allowed].some(base => base === endpoint.replace(/\/+$/,''))) {
      res.status(403).json({error:'Agent endpoint must be loopback or explicitly allowlisted'});return;
    }
    if (!['http:','https:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.hash) {
      res.status(400).json({error:'Invalid registered agent endpoint'});return;
    }
    discoveredEndpoints.add(parsed.href);
    try {await relay(res,await forward(endpoint,'POST',{message,contextId,context}));}
    finally {discoveredEndpoints.delete(parsed.href);}
  } catch(e) {fail(res,e);}
});
app.listen(PORT,'127.0.0.1',()=>console.log(`CALIAN proxy: http://127.0.0.1:${PORT}`));
