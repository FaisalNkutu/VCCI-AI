import 'dotenv/config';
import express from 'express';
import { Readable } from 'node:stream';
import { WebSocketServer, WebSocket } from 'ws';
import { createServer } from 'node:http';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const app = express();
app.use(express.json({limit:'1mb'}));
const PORT = Number(process.env.PORT || 8797);
const PYTHON_SERVICE_URL = process.env.PYTHON_SERVICE_URL || 'http://127.0.0.1:9999';
// The selection-only Ollama service is separate from the executable Python A2A service.
const OLLAMA_ROUTER_URL = process.env.OLLAMA_ROUTER_URL || 'http://127.0.0.1:9999';
const AI_CORE_REGISTRY_URL = process.env.AI_CORE_REGISTRY_URL || 'http://127.0.0.1:9801/api/ai-core/agents';
const PARADIGM_BRIDGE_TOKEN = process.env.PARADIGM_BRIDGE_TOKEN || '';
let paradigmSnapshot: {commandList: Record<string,string[]>;agentURLs:Record<string,string>} | null = null;
// Explicitly configured upstreams only; the browser cannot supply arbitrary target URLs.
const discoveredEndpoints = new Set<string>();
const allowed = new Set([PYTHON_SERVICE_URL,OLLAMA_ROUTER_URL,AI_CORE_REGISTRY_URL,...(process.env.AI_CORE_ENDPOINTS || '').split(',')].filter(Boolean).map(s=>s.replace(/\/+$/,'')));
function validate(urlText:string) {
 const u = new URL(urlText);
 if (!['http:','https:'].includes(u.protocol) || u.username || u.password || u.hash) throw new Error('Invalid upstream URL');
 if (!discoveredEndpoints.has(u.href) && ![...allowed].some(base => { const b=new URL(base); return u.origin===b.origin && (u.pathname===b.pathname || ((base===PYTHON_SERVICE_URL && ['/','/agents','/router/select'].includes(u.pathname)) || (base===OLLAMA_ROUTER_URL && u.pathname==='/router/select'))); })) throw new Error('Upstream not allowlisted');
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
// The local assistant executes against Ollama even when the optional Python router is offline.
const LOCAL_OLLAMA_AGENT={id:'local-ollama',name:'Local Ollama Assistant',description:'Local llama3.2 chat and translation',type:'north',streaming:false,configured:true,active:true,endpoint:'local',skills:[]};
app.get('/api/agents',async(_req,res)=>{
 try {
  const upstream=await forward(PYTHON_SERVICE_URL.replace(/\/+$/,'')+'/agents','GET');
  if(!upstream.ok)throw new Error(`Python router HTTP ${upstream.status}`);
  const data:any=await upstream.json();
  const agents=Array.isArray(data?.agents)?data.agents.filter((a:any)=>a?.id!=='local-ollama'):[];
  agents.unshift(LOCAL_OLLAMA_AGENT);
  res.json({...data,agents,count:agents.length});
 }catch{res.json({agents:[LOCAL_OLLAMA_AGENT],count:1,discovery:'local-ollama; Python router offline'});}
});
app.post('/api/ollama/chat',async(req,res)=>{
 const {message,context}=req.body||{};
 if(typeof message!=='string'||!message.trim()||message.length>16000){res.status(400).json({error:'Message must be 1-16000 characters'});return;}
 const history=Array.isArray(context?.conversationHistory)?context.conversationHistory.slice(-12):[];
 const system=[context?.systemInstructions,context?.userContext&&`User context: ${context.userContext}`,context?.taskContext&&`Task context: ${context.taskContext}`].filter(x=>typeof x==='string'&&x.trim()).join('\n');
 const messages=[...(system?[{role:'system',content:system}]:[]),...history.filter((t:any)=>['user','assistant'].includes(t?.role)&&typeof t.content==='string').map((t:any)=>({role:t.role,content:t.content.slice(0,16000)})),{role:'user',content:message}];
 try{
  const upstream=await fetch((process.env.OLLAMA_URL||'http://127.0.0.1:11434').replace(/\/+$/,'')+'/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({model:process.env.OLLAMA_MODEL||'llama3.2:latest',stream:false,messages}),signal:AbortSignal.timeout(180000)});
  if(!upstream.ok){res.status(502).json({error:`Ollama HTTP ${upstream.status}: ${(await upstream.text()).slice(0,500)}`});return;}
  const output:any=await upstream.json();
  const answer=output?.message?.content;
  if(typeof answer!=='string'||!answer.trim())throw new Error('Ollama returned no text');
  res.json({text:answer,model:output.model});
 }catch(e){res.status(502).json({error:'Cannot reach Ollama at '+(process.env.OLLAMA_URL||'http://127.0.0.1:11434')+': '+(e instanceof Error?e.message:String(e))});}
});
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
// Route against the server-discovered registry, never caller-supplied endpoints.
// Ollama selects only among server-discovered executable agents. Never execute local-ollama placeholder.
const routeWords=(v:string)=>new Set((v.toLowerCase().match(/[a-z0-9]+/g)||[]).filter(w=>w.length>2));
const routeGroups=[['translate','translation','translator','french','spanish','german','language'],['dice','roll','random'],['database','sql','query','records','tables','table','db'],['speech','transcribe','transcription','audio','voice'],['social','media','linkedin','post','posts'],['json','structured','schema','format','fields']];
function expandRoute(v:string){const words=routeWords(v);for(const group of routeGroups)if(group.some(w=>words.has(w)))group.forEach(w=>words.add(w));return words;}
function overlaps(a:Set<string>,b:Set<string>){let n=0;for(const w of a)if(b.has(w))n++;return n;}
type RouteAgent={id:string;name:string;type:'north'|'aicore';description:string;commands:string[]};
app.post('/api/router/select',async(req,res)=>{try{
 const message=req.body?.message;
 if(typeof message!=='string'||!message.trim()||message.length>20000){res.status(400).json({error:'Invalid message'});return;}
 const [pythonResult,coreResult]=await Promise.allSettled([forward(PYTHON_SERVICE_URL.replace(/\/+$/,'')+'/agents','GET'),forward(AI_CORE_REGISTRY_URL,'GET')]);
 const trusted:RouteAgent[]=[];
 if(pythonResult.status==='fulfilled'&&pythonResult.value.ok){const data:any=await pythonResult.value.json();for(const a of (Array.isArray(data?.agents)?data.agents:[]))if(typeof a.id==='string'&&a.id)trusted.push({id:a.id,name:String(a.name||a.id),type:'north',description:String(a.description||''),commands:Array.isArray(a.skills)?a.skills.flatMap((x:any)=>[String(x.name||''),String(x.description||''),...(Array.isArray(x.tags)?x.tags:[])]):[]});}
 if(coreResult.status==='fulfilled'&&coreResult.value.ok){const data:any=await coreResult.value.json();if(data?.agents&&!Array.isArray(data.agents)){for(const [id,url] of Object.entries(data.agents))if(typeof url==='string')trusted.push({id,name:id,type:'aicore',description:'',commands:[]});}else if(Array.isArray(data?.agents)){for(const a of data.agents)if(a.id||a.name)trusted.push({id:String(a.id||a.name),name:String(a.name||a.id),type:'aicore',description:String(a.description||''),commands:Array.isArray(a.commands)?a.commands:[]});}else{const commands=data?.commandList||{};const urls=data?.agentURLs||{};for(const id of new Set([...Object.keys(commands),...Object.keys(urls)]))trusted.push({id,name:id,type:'aicore',description:'',commands:Array.isArray(commands[id])?commands[id]:[]});}}
 if(!trusted.length&&paradigmSnapshot){for(const id of new Set([...Object.keys(paradigmSnapshot.commandList),...Object.keys(paradigmSnapshot.agentURLs)]))trusted.push({id,name:id,type:'aicore',description:'',commands:paradigmSnapshot.commandList[id]||[]});}
 const requested=Array.isArray(req.body?.agents)?req.body.agents:[];
 // Selection is advisory: UI-discovered metadata may be used for selection, never for execution URLs.
 const candidates:RouteAgent[]=requested.filter((r:any)=>r && typeof r.id==='string' && r.id.length<150 && ['north','aicore'].includes(r.type))
  .map((r:any)=>{const known=trusted.find(t=>t.id===r.id && t.type===r.type);return known || {id:r.id,name:String(r.name||r.id).slice(0,200),type:r.type,description:String(r.description||'').slice(0,2000),commands:Array.isArray(r.commands)?r.commands.filter((c:any)=>typeof c==='string').slice(0,50):[]};});
 if(!candidates.length){res.json({agent:null,confidence:0,reason:'No discovered agents supplied by Electron UI'});return;}
 // PRIMARY: invoke local Ollama directly, rather than depending on the Python router.
 try {
  const catalog=candidates.map(a=>({id:a.id,name:a.name,type:a.type,description:a.description,commands:a.commands}));
  const ollama=await fetch((process.env.OLLAMA_URL||'http://127.0.0.1:11434').replace(/\/+$/,'')+'/api/chat',{
   method:'POST',headers:{'Content-Type':'application/json'},signal:AbortSignal.timeout(30000),
   body:JSON.stringify({model:process.env.OLLAMA_MODEL||'llama3.2:latest',stream:false,format:'json',options:{temperature:0},messages:[
    {role:'system',content:'You are CALIAN AI agent selector. Choose ONE best agent from this catalog. Reply ONLY valid JSON {"agentId":"exact-id","reason":"short explanation"}. Never invent IDs. Catalog: '+JSON.stringify(catalog)},
    {role:'user',content:message}
   ]})
  });
  if(!ollama.ok)throw new Error('Ollama HTTP '+ollama.status);
  const output:any=await ollama.json();
  const decision=JSON.parse(String(output?.message?.content||'{}'));
  const selected=candidates.find(a=>a.id===decision.agentId);
  if(selected){res.json({agent:{id:selected.id,name:selected.name,type:selected.type},agentId:selected.id,agentName:selected.name,confidence:.8,reason:String(decision.reason||'Chosen by Ollama').slice(0,500),method:'ollama'});return;}
  throw new Error('Ollama did not return a registered agent ID');
 }catch(error){console.warn('Direct Ollama unavailable, attempting Python Ollama router:',error);}
 // Secondary: existing Python Ollama router, then trusted metadata fallback.
 try {
  const upstream=await forward(OLLAMA_ROUTER_URL.replace(/\/+$/,'')+'/router/select','POST',{
   message,agents:candidates.map(a=>({id:a.id,name:a.name,type:a.type,description:a.description,commands:a.commands,skills:a.commands.map((c,i)=>({id:`command-${i}`,name:c,description:c,tags:[],examples:[]}))})),contextId:req.body?.contextId,context:req.body?.context
  });
  if(upstream.ok){const choice:any=await upstream.json();const id=String(choice.agentId||choice.agent?.id||'');const selected=candidates.find(a=>a.id===id);
   if(selected&&choice.method==='ollama'){res.json({agent:{id:selected.id,name:selected.name,type:selected.type},agentId:selected.id,agentName:selected.name,confidence:Number(choice.confidence)||.8,reason:String(choice.reason||'Selected by Python Ollama'),method:'ollama'});return;}}
 }catch(error){console.warn('Python router unavailable; using metadata fallback:',error);}
 const query=expandRoute(message);const ranked=candidates.map(a=>({agent:a,score:5*overlaps(query,routeWords(a.name))+2*overlaps(query,routeWords(a.commands.join(' ')))+overlaps(query,routeWords(a.description))})).sort((a,b)=>b.score-a.score||a.agent.id.localeCompare(b.agent.id));
 const best=ranked[0];if(best.score===0&&ranked.length!==1){res.json({agent:null,confidence:0,reason:'No agent capability matched the request'});return;}
 if(ranked.length>1&&ranked[1].score===best.score){res.json({agent:null,confidence:0,reason:'Ambiguous match between multiple agents'});return;}
 res.json({agent:{id:best.agent.id,name:best.agent.name,type:best.agent.type},agentId:best.agent.id,agentName:best.agent.name,confidence:best.score?Math.min(.95,.55+best.score*.04):.5,reason:best.score?'Selected using trusted agent metadata':'Only one registered agent is available',method:'metadata'});
 }catch(e){fail(res,e);}});
app.post('/api/a2a',async(req,res)=>{
  // The bundled Python router exposes discovery and selection, NOT an A2A executor.
  // Reject unsupported execution clearly instead of forwarding to its missing '/' route.
  res.status(503).json({error:'Python A2A execution service is not configured. Local Ollama Assistant works through /api/ollama/chat. Configure PYTHON_SERVICE_URL to a real A2A executor for other Python agents.'});
});
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
    // The DBAgent Python A2A service (typically port 5022) requires a JSON-RPC
    // envelope. Other Paradigm/AI Core agents retain their existing request format.
    // Override CALIAN_A2A_EXECUTION_PORT if the Python service is remapped.
    const a2aPort = String(process.env.CALIAN_A2A_EXECUTION_PORT || '5022');
    const isPythonA2A = parsed.port === a2aPort;
    const payload = isPythonA2A ? {
      jsonrpc: '2.0',
      id: randomUUID(),
      method: 'message/send',
      params: {
        message: {
          role: 'user',
          messageId: randomUUID(),
          contextId,
          parts: [{kind: 'text', text: message}],
          metadata: {agent_id: agentId, context}
        }
      }
    } : {message,contextId,context};
    if (isPythonA2A) console.info(`[CALIAN] Sending A2A message/send to ${parsed.origin} for ${agentId}`);
    try {await relay(res,await forward(endpoint,'POST',payload));}
    finally {discoveredEndpoints.delete(parsed.href);}
  } catch(e) {fail(res,e);}
});
// Serve the packaged Angular UI from the same loopback origin.
const staticDir = process.env.CALIAN_STATIC_DIR;
if (staticDir) {
 app.use(express.static(staticDir));
 app.get(/^(?!\/api\/).*/, (_req,res)=>res.sendFile(path.join(staticDir,'index.html')));
}
const server = createServer(app);
const sockets = new WebSocketServer({server,path:'/ws',maxPayload:1024*1024});
sockets.on('connection',(socket,request)=>{
 const origin=request.headers.origin;
 if(origin && !/^http:\/\/127\.0\.0\.1:(4300|8797)$/.test(origin) && !/^http:\/\/localhost:(4300|8797)$/.test(origin)) {socket.close(1008,'Invalid origin');return;}
 const send=(data:unknown)=>{if(socket.readyState===WebSocket.OPEN)socket.send(JSON.stringify(data));};
 socket.on('message',async(raw)=>{
  let id:string|undefined;
  try {
   const input=JSON.parse(raw.toString());id=input.id;
   if(typeof id!=='string'||id.length>100||!['/api/health','/api/agents','/api/ai-core/agents','/api/router/select','/api/a2a','/api/ai-core/execute','/api/ollama/chat'].includes(input.path)||!['GET','POST'].includes(input.method))throw new Error('Invalid socket request');
   // Existing trusted Express routes retain all validation and upstream protections.
   const response=await fetch(`http://127.0.0.1:${PORT}${input.path}`,{method:input.method,headers:{'Content-Type':'application/json'},body:input.method==='POST'?JSON.stringify(input.body):undefined,signal:AbortSignal.timeout(180000)});
   if(!response.ok){send({id,type:'error',error:`HTTP ${response.status}: ${await response.text()}`});return;}
   if(input.path==='/api/a2a'){
    if(!response.body)throw new Error('Missing A2A stream');
    for await(const chunk of Readable.fromWeb(response.body as any))send({id,type:'chunk',data:Buffer.from(chunk).toString('utf8')});
    send({id,type:'done'});
   }else send({id,type:'result',data:await response.json()});
  }catch(e){send({id,type:'error',error:e instanceof Error?e.message:String(e)});}
 });
});
server.listen(PORT,'127.0.0.1',()=>console.log(`CALIAN WebSocket backend: ws://127.0.0.1:${PORT}/ws`));
