const {chromium}=require('playwright'),http=require('http'),fs=require('fs'),path=require('path'),crypto=require('crypto'),assert=require('assert/strict');
const root=path.resolve(__dirname,'../../app/src/main/assets/reader'),hash=x=>crypto.createHash('sha256').update(x).digest('hex');
const allowed=['host.html','host.js','host2.html','host2.js','host3.html','host3.js','viewport.js','input.js','vendor/foliate/paginator.js','vendor/foliate/fixed-layout.js'];
let protocol=1,fixed=false,sourceWriting='horizontal-tb',sourceDirection='ltr',sectionDirections={};
let text='ACTUAL TRUSTED INPUT 中文😀 '.repeat(30);
const server=http.createServer((req,res)=>{
 const name=req.url.replace(/^\/reader\/input\/revision\//,'');
 if(name==='resources/page.svg'){res.writeHead(200,{'Content-Type':'image/svg+xml'});res.end('<svg xmlns="http://www.w3.org/2000/svg" width="4000" height="6000"><rect width="4000" height="6000" fill="pink"/></svg>');return}
 if(/^sections\/[0-9]+\.html$/.test(name)){res.writeHead(200,{'Content-Type':'text/html','Content-Security-Policy':"default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src 'self'"});res.end(`<html><head>${fixed?'<meta name="viewport" content="width=4000,height=6000">':`<style>html{writing-mode:${sourceWriting};direction:${sectionDirections[Number(name.match(/[0-9]+/)[0])]??sourceDirection}}</style>`}</head><body data-reader-canonical-length="${text.length}" data-reader-canonical-sha="${hash(text)}">${fixed?'<img src="../resources/page.svg" width="4000" height="6000" style="display:block"/>':''}<p style="margin:0;${fixed?'display:none':''}"><span data-block="0" data-length="${text.length}">${text}</span></p></body></html>`);return}
 if(!allowed.includes(name)){res.writeHead(403);res.end();return}res.writeHead(200,{'Content-Type':name.endsWith('.js')?'text/javascript':'text/html'});res.end(fs.readFileSync(path.join(root,name)));
});
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({executablePath:process.env.READER_CHROMIUM,headless:true}),results=[];
 try {for(const version of [1,2]) {
  protocol=version;fixed=false;text='首末行完整文字。'.repeat(300);const page=await browser.newPage({viewport:{width:400,height:600}}),packets=[];
  await page.exposeFunction('receive',raw=>packets.push(JSON.parse(raw)));await page.addInitScript(()=>window.ReaderEvents={postMessage:raw=>window.receive(raw)});
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/input/revision/${version===1?'host':'host2'}.html`);await page.waitForFunction(()=>window.readerHost);
  const payload={sectionCount:1,target:version===1?{section:0,blockId:'0',character:0}:{section:0,kind:'TEXT',offset:0},direction:'ltr',flow:'scrolled',paginationBase:'scroll-insets',nativeInsets:{left:0,top:64,right:0,bottom:72,width:400},geometry:{gap:'7%','margin-top':'24px','margin-bottom':'24px','margin-left':'16px','margin-right':'16px'},beforeAuthor:'html,body{margin:0;padding:0}body{font:20px serif;line-height:1.5}',afterAuthor:''};
  await page.evaluate(({version,payload})=>readerHost.command({protocol:version,sessionId:'input',revision:'revision',commandId:1,layoutEpoch:1,intentToken:'open',intentCause:'Restore',type:'open',payload}),{version,payload});
  try{await page.waitForFunction(()=>readerHost.diagnostics().events.some(e=>e.type==='relocated'||e.type==='error'))}catch(e){console.error(JSON.stringify({packets,diagnostics:await page.evaluate(()=>readerHost.diagnostics())}));throw e}assert(!packets.some(e=>e.type==='error'),JSON.stringify(packets));
  const geometry=async(last)=>page.evaluate(last=>{const p=document.getElementById('renderer'),{doc}=p.getContents()[0],frame=doc.defaultView.frameElement.getBoundingClientRect(),n=doc.querySelector('[data-block]').firstChild,r=doc.createRange();r.setStart(n,last?n.length-1:0);r.setEnd(n,last?n.length:1);const b=r.getBoundingClientRect(),s=doc.defaultView.getComputedStyle(doc.documentElement);return {top:b.top+frame.top,bottom:b.bottom+frame.top,paddingTop:s.paddingTop,paddingBottom:s.paddingBottom,viewport:innerHeight}},last);
  const first=await geometry(false);assert(first.top>=88,JSON.stringify(first));assert.equal(first.paddingTop,'88px');assert.equal(first.paddingBottom,'96px');
  await page.evaluate(()=>{const c=document.getElementById('renderer').shadowRoot.querySelector('#container');c.scrollTop=c.scrollHeight});await page.waitForTimeout(500);
  const last=await geometry(true);assert(last.bottom<=504,JSON.stringify(last));assert(last.top>0,JSON.stringify(last));
  const count=packets.filter(e=>e.type==='tapRequested').length;await page.mouse.click(200,300);await page.waitForTimeout(500);assert.equal(packets.filter(e=>e.type==='tapRequested').length,count+1);assert.equal(packets.filter(e=>e.type==='tapRequested').at(-1).region,'CENTER');
  results.push({protocol:version,first,last,centerTap:true});await page.close();
 }console.log(JSON.stringify(results,null,2));}finally {await browser.close();server.close()}
})().catch(e=>{console.error(e);server.close();process.exitCode=1});
