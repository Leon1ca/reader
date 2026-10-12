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
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({executablePath:process.env.READER_CHROMIUM,headless:true});const results=[];
 try {
 for(const kind of ['TXT','EPUB','FXL']) {
  fixed=kind==='FXL';protocol=kind==='TXT'?1:2;const packets=[],errors=[];const page=await browser.newPage({viewport:{width:400,height:600}});
  page.on('pageerror',e=>errors.push(String(e)));await page.exposeFunction('receive',raw=>packets.push(JSON.parse(raw)));await page.addInitScript(()=>window.ReaderEvents={postMessage:raw=>window.receive(raw)});
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/input/revision/${fixed?'host3':protocol===2?'host2':'host'}.html`);await page.waitForFunction(()=>window.readerHost);
  const payload={sectionCount:3,target:{section:0,...(fixed?{kind:'PAGE',x:0,y:0}:protocol===1?{blockId:'0',character:0}:{kind:'TEXT',offset:0})},direction:'ltr',flow:'paginated',zoom:'fit-page',spread:'SINGLE',theme:'white',paginationBase:'input',profileId:'input',geometry:{gap:'7%','margin-top':'12px','margin-bottom':'12px','margin-left':'12px','margin-right':'12px'},beforeAuthor:'html,body{margin:0;padding:0}body{font:20px serif;line-height:1.5}',afterAuthor:'',fixedPages:Array.from({length:3},(_,index)=>({index,width:4000,height:6000,spread:'none'}))};
  await page.evaluate(({protocol,payload})=>readerHost.command({protocol,sessionId:'input',revision:'revision',commandId:1,layoutEpoch:1,intentToken:'open',intentCause:'Restore',type:'open',payload}),{protocol,payload});
  await page.waitForFunction(()=>readerHost.diagnostics().events.some(e=>e.type==='relocated'||e.type==='error'),null,{timeout:15000});assert(!packets.some(e=>e.type==='error'),JSON.stringify(packets));
  const tap=async(x,y=260)=>{await page.mouse.click(x,y);await page.waitForTimeout(450)};
  await page.evaluate(()=>document.getElementById('renderer').getContents()[0].doc.body.click());await page.waitForTimeout(400);assert.equal(packets.filter(e=>e.type==='tapRequested').length,0);
  await tap(25);await tap(200);await tap(375);assert.deepEqual(packets.filter(e=>e.type==='tapRequested').map(e=>e.region),['LEFT','CENTER','RIGHT']);
  assert.equal(packets.filter(e=>e.type==='relocated').length,1,'host never pages ordinary single taps itself');
  if(fixed) {const count=packets.filter(e=>e.type==='tapRequested').length;await page.mouse.dblclick(200,550);await page.waitForTimeout(450);assert.equal(packets.filter(e=>e.type==='tapRequested').length,count);assert(packets.some(e=>e.type==='zoomRequested'))}
  else {await page.evaluate(({protocol,payload})=>readerHost.command({protocol,sessionId:'input',revision:'revision',commandId:2,layoutEpoch:2,intentToken:'flow',intentCause:'Reflow',type:'setRules',payload:{...payload,flow:'scrolled'}}),{protocol,payload});await page.waitForFunction(()=>readerHost.diagnostics().events.some(e=>e.commandId===2&&e.type==='relocated'));await tap(25);assert.equal(packets.filter(e=>e.type==='tapRequested').at(-1).region,'CENTER')}
  assert.deepEqual(errors,[]);results.push({kind,trustedRegions:true,syntheticRejected:true,singleHostNoFlip:true});await page.close();
 }
 // Exercise the same physical-pixel payload as native consumers, including a
 // distant source anchor. A first-page Ready alone did not expose NaN margins.
 fixed=false;text='ACTUAL TRUSTED INPUT 中文😀 '.repeat(300);
 for(const version of [1,2])for(const mode of ['absent','zero','chrome','scaled']) {
  protocol=version;const page=await browser.newPage({viewport:{width:400,height:600}}),packets=[],errors=[];
  page.on('pageerror',e=>errors.push(String(e)));await page.exposeFunction('receive',raw=>packets.push(JSON.parse(raw)));await page.addInitScript(()=>window.ReaderEvents={postMessage:raw=>window.receive(raw)});
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/input/revision/${version===1?'host':'host2'}.html`);await page.waitForFunction(()=>window.readerHost);
  const geometry={gap:'7%','margin-top':'12px','margin-bottom':'12px','margin-left':'12px','margin-right':'12px'};
  const payload={sectionCount:1,target:version===1?{section:0,blockId:'0',character:6000}:{section:0,kind:'TEXT',offset:6000},direction:'ltr',flow:'paginated',paginationBase:'input-insets',geometry,beforeAuthor:'html,body{margin:0;padding:0}body{font:20px serif;line-height:1.5}',afterAuthor:''};
  const factor=mode==='scaled'?3.25:1;
  if(mode!=='absent')payload.nativeInsets={left:0,top:mode==='zero'?0:64*factor,right:0,bottom:mode==='zero'?0:184*factor,width:400*factor};
  const command=async(id,value)=>{
   await page.evaluate(({version,id,value})=>readerHost.command({protocol:version,sessionId:'input',revision:'revision',commandId:id,layoutEpoch:id,intentToken:`insets-${id}`,intentCause:id===1?'Restore':'Reflow',type:id===1?'open':'setRules',payload:value}),{version,id,value});
   await page.waitForFunction(id=>readerHost.diagnostics().events.some(e=>e.commandId===id&&(e.type==='relocated'||e.type==='error')),id,{timeout:15000});
   assert(!packets.some(e=>e.type==='error'),JSON.stringify(packets));
   const actual=await page.evaluate(()=>{const p=document.getElementById('renderer'),d=readerHost.diagnostics();return {anchor:d.anchorGeometry,target:d.target,margins:['top','bottom','left','right'].map(s=>p.getAttribute(`margin-${s}`))}});
   assert(actual.anchor.visible,JSON.stringify(actual));assert.equal(actual.target[version===1?'character':'offset'],6000);
   const top=mode==='absent'||mode==='zero'?12:76,bottom=mode==='absent'||mode==='zero'?12:196;
   assert.deepEqual(actual.margins,[`${top}px`,`${bottom}px`,'12px','12px']);
   assert(actual.anchor.top>=top && actual.anchor.top<600-bottom,JSON.stringify(actual));
   return actual;
  };
  const first=await command(1,payload);
  await page.setViewportSize({width:480,height:600});
  const resized={...payload,geometry:{...geometry},...(payload.nativeInsets?{nativeInsets:{...payload.nativeInsets,width:480*factor}}:{})};
  const after=await command(2,resized);
  // Unsupported length units are explicitly rejected; they never reach the
  // paginator as a misleading parseFloat value.
  await page.evaluate(async()=>{const {applyInsets}=await import('./input.js');for(const base of ['1em','10%','calc(12px + 0px)','Infinitypx']){let rejected=false;try{applyInsets({nativeInsets:{left:0,top:0,right:0,bottom:0,width:innerWidth},geometry:{'margin-top':base}})}catch{rejected=true}if(!rejected)throw Error('Unsupported pixel margin accepted')}});
  assert.deepEqual(errors,[]);results.push({protocol:version,insets:mode,initial:first,resized:after});await page.close();
 }
 // A wide host's gutter is not its first pagination tile. At fractional tile
 // widths the native integer scroll value must still identify the next tile.
 fixed=false;
 for(const layout of [{version:1,writing:'horizontal-tb',direction:'ltr'},{version:2,writing:'horizontal-tb',direction:'ltr'},
  {version:2,writing:'horizontal-tb',direction:'rtl'}]) {
  protocol=layout.version;sourceWriting=layout.writing;sourceDirection=layout.direction;const page=await browser.newPage({viewport:{width:851,height:883}}),packets=[],errors=[];
  page.on('pageerror',e=>errors.push(String(e)));await page.exposeFunction('receive',raw=>packets.push(JSON.parse(raw)));await page.addInitScript(()=>window.ReaderEvents={postMessage:raw=>window.receive(raw)});
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/input/revision/${protocol===1?'host':'host2'}.html`);await page.waitForFunction(()=>window.readerHost);
  const payload={sectionCount:1,target:protocol===1?{section:0,blockId:'0',character:0}:{section:0,kind:'TEXT',offset:0},direction:layout.direction,flow:'paginated',paginationBase:'tile-origin',
   nativeInsets:{left:0,top:64,right:0,bottom:184,width:851},geometry:{gap:'7%','margin-top':'24px','margin-bottom':'24px','margin-left':'16px','margin-right':'16px','max-column-count':1},
   beforeAuthor:'html,body{margin:0;padding:0}body{font:20px serif;line-height:1.5}',afterAuthor:''};
  const command=async(id,type,value)=>{
   await page.evaluate(({version,id,type,value})=>readerHost.command({protocol:version,sessionId:'input',revision:'revision',commandId:id,layoutEpoch:version===1?id:1,intentToken:`tile-${id}`,intentCause:id===1?'Restore':'UserNavigation',type,payload:value}),{version:protocol,id,type,value});
   await page.waitForFunction(id=>readerHost.diagnostics().events.some(e=>e.commandId===id&&(e.type==='relocated'||e.type==='error')),id,{timeout:15000});
   assert(!packets.some(e=>e.type==='error'),JSON.stringify(packets));
   const event=packets.filter(e=>e.commandId===id&&e.type==='relocated').at(-1),actual=await page.evaluate(()=>{const p=document.getElementById('renderer');return {page:p.page,start:p.start,size:p.size,glyph:readerHost.diagnostics().anchorGeometry}});
   assert(actual.glyph.visible,JSON.stringify(actual));assert.equal(event.viewport.samples[0].page,actual.page+1);
   return {source:event.target[protocol===1?'character':'offset'],sample:event.viewport.samples[0],engine:actual};
  };
  const first=await command(1,'open',payload),next=await command(2,'next',{}),back=await command(3,'prev',{});
  const evidence=JSON.stringify({layout,first,next,back});
  assert.equal(next.sample.page,first.sample.page+1,evidence);assert(next.source>first.source,evidence);assert.equal(back.sample.page,first.sample.page,evidence);assert.equal(back.source,first.source,evidence);
  assert.deepEqual(errors,[]);results.push({tileOrigin:layout,first,next,back});await page.close();
 }
 // The reported page direction follows the section on screen. With a `default` book
 // direction an LTR -> RTL chapter switch used to keep the first mounted section's value.
 fixed=false;protocol=2;sourceWriting='horizontal-tb';sourceDirection='ltr';text='DIRECTION CHAPTER 中文 '.repeat(120);
 for(const bookDirection of ['default','ltr','rtl']) {
  sectionDirections={0:'ltr',1:'rtl',2:'ltr'};
  const page=await browser.newPage({viewport:{width:400,height:600}}),packets=[],errors=[];
  page.on('pageerror',e=>errors.push(String(e)));await page.exposeFunction('receive',raw=>packets.push(JSON.parse(raw)));await page.addInitScript(()=>window.ReaderEvents={postMessage:raw=>window.receive(raw)});
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/input/revision/host2.html`);await page.waitForFunction(()=>window.readerHost);
  const payload={sectionCount:3,target:{section:0,kind:'TEXT',offset:0},direction:bookDirection,flow:'paginated',paginationBase:'direction',geometry:{gap:'7%','margin-top':'12px','margin-bottom':'12px','margin-left':'12px','margin-right':'12px'},beforeAuthor:'html,body{margin:0;padding:0}body{font:20px serif;line-height:1.5}',afterAuthor:''};
  const seen=[];
  for(const [id,section] of [[1,0],[2,1],[3,2],[4,1],[5,0]]) {
   await page.evaluate(({id,section,payload})=>readerHost.command({protocol:2,sessionId:'input',revision:'revision',commandId:id,layoutEpoch:1,intentToken:`direction-${id}`,intentCause:id===1?'Restore':'UserNavigation',type:id===1?'open':'goTo',payload:{...payload,target:{section,kind:'TEXT',offset:0}}}),{id,section,payload});
   await page.waitForFunction(id=>readerHost.diagnostics().events.some(e=>e.commandId===id&&(e.type==='relocated'||e.type==='error')),id,{timeout:15000});
   assert(!packets.some(e=>e.type==='error'),JSON.stringify(packets));
   const event=packets.filter(e=>e.commandId===id&&e.type==='relocated').at(-1);
   assert.equal(event.target.section,section);seen.push(event.pageDirection);
  }
  const expected=bookDirection==='default'?['ltr','rtl','ltr','rtl','ltr']:Array(5).fill(bookDirection);
  assert.deepEqual(seen,expected,`bookDirection=${bookDirection}`);
  assert.deepEqual(errors,[]);results.push({pageDirection:bookDirection,seen});await page.close();
 }
 sectionDirections={};
 fixed=true;protocol=2;
 for(const mode of ['absent','zero','chrome','scaled']) {
  const page=await browser.newPage({viewport:{width:400,height:600}}),packets=[],errors=[];
  page.on('pageerror',e=>errors.push(String(e)));await page.exposeFunction('receive',raw=>packets.push(JSON.parse(raw)));await page.addInitScript(()=>window.ReaderEvents={postMessage:raw=>window.receive(raw)});
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/input/revision/host3.html`);await page.waitForFunction(()=>window.readerHost);
  const factor=mode==='scaled'?3.25:1;
  const payload={sectionCount:1,target:{section:0,kind:'PAGE',x:0,y:0},direction:'ltr',flow:'paginated',zoom:'fit-page',spread:'SINGLE',theme:'white',paginationBase:'fixed-insets',fixedPages:[{index:0,width:4000,height:6000,spread:'none'}]};
  if(mode!=='absent')payload.nativeInsets={left:0,top:mode==='zero'?0:64*factor,right:0,bottom:mode==='zero'?0:184*factor,width:400*factor};
  const command=async(id,value)=>{
   await page.evaluate(({id,value})=>readerHost.command({protocol:2,sessionId:'input',revision:'revision',commandId:id,layoutEpoch:id,intentToken:`fixed-${id}`,intentCause:id===1?'Restore':'Reflow',type:id===1?'open':'setRules',payload:value}),{id,value});
   await page.waitForFunction(id=>readerHost.diagnostics().events.some(e=>e.commandId===id&&(e.type==='relocated'||e.type==='error')),id,{timeout:15000});
   assert(!packets.some(e=>e.type==='error'),JSON.stringify(packets));
   const actual=await page.evaluate(()=>{const r=document.getElementById('renderer').getBoundingClientRect(),d=readerHost.diagnostics();return {anchor:d.anchorGeometry,target:d.target,rect:{top:r.top,bottom:r.bottom,width:r.width}}});
   const chrome=mode==='chrome'||mode==='scaled';assert.equal(actual.rect.top,chrome?64:0);assert.equal(actual.rect.bottom,chrome?416:600);
   assert(actual.anchor.visible,JSON.stringify(actual));assert.equal(actual.target.section,0);assert.equal(actual.target.x,0);assert.equal(actual.target.y,0);
   return actual;
  };
  const first=await command(1,payload);await page.setViewportSize({width:480,height:600});
  const after=await command(2,{...payload,...(payload.nativeInsets?{nativeInsets:{...payload.nativeInsets,width:480*factor}}:{})});
  assert.equal(after.rect.width,480);assert.deepEqual(errors,[]);results.push({kind:'FXL',insets:mode,initial:first,resized:after});await page.close();
 }
 console.log(JSON.stringify({results,bindings:Object.fromEntries(allowed.filter(n=>n.endsWith('.js')).map(n=>[n,hash(fs.readFileSync(path.join(root,n)))]))}));
 } finally {await browser.close();server.close()}
})().catch(e=>{console.error(e.stack);server.close();process.exitCode=1});
