/* Production dense source geometry: compare every code unit against actual glyph rectangles. */
const {chromium}=require('playwright'),http=require('http'),fs=require('fs'),path=require('path'),crypto=require('crypto'),assert=require('assert/strict');
const root=path.resolve(__dirname,'../../app/src/main/assets/reader');
const files=['host.html','host.js','host2.html','host2.js','viewport.js','vendor/foliate/paginator.js'];
const frozen=Object.fromEntries(files.map(name=>[name,fs.readFileSync(path.join(root,name))]));
const hash=x=>crypto.createHash('sha256').update(x).digest('hex');
const bindings=Object.fromEntries(files.map(name=>[name,hash(frozen[name])]));
const fixtures=[
 {name:'1200_adjacent_inline_spans',html:Array.from({length:1200},(_,i)=>`<span>${String.fromCharCode(65+i%26)}</span>`).join(''),canonical:Array.from({length:1200},(_,i)=>String.fromCharCode(65+i%26)).join('')},
 {name:'600_ruby_base_annotation',html:Array.from({length:600},()=>'<ruby><span>字</span><rt><span>zi</span></rt></ruby>').join(''),canonical:'字zi'.repeat(600)},
 {name:'600_hidden_inline_gaps',html:'<span>A</span><span style="display:none">H</span><span>B</span>'.repeat(600),canonical:'AHB'.repeat(600)},
 {name:'600_alternating_span_ruby',html:Array.from({length:600},()=>'<span>A</span><ruby>字<rt>zi</rt></ruby><span>B</span>').join(''),canonical:'A字ziB'.repeat(600)}
];
let active,packets=[],errors=[];
const server=http.createServer((req,res)=>{
 const file=req.url.replace(/^\/reader\/dense\/revision\//,'');
 if(file==='sections/0.html') {res.writeHead(200,{'Content-Type':'text/html; charset=utf-8','Content-Security-Policy':"default-src 'none'; script-src 'none'; style-src 'unsafe-inline'"});res.end(active.document);return}
 if(!frozen[file]) {res.writeHead(403);res.end();return}
 res.writeHead(200,{'Content-Type':file.endsWith('.js')?'text/javascript':'text/html'});res.end(frozen[file]);
});
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({executablePath:process.env.READER_CHROMIUM,headless:true});
 const results=[];
 try {for(const fixture of fixtures)for(const protocol of [1,2]){
  active={...fixture,document:`<!doctype html><html><body data-reader-canonical-length="${fixture.canonical.length}" data-reader-canonical-sha="${hash(fixture.canonical)}"><p>${protocol===1?`<span data-block="0" data-length="${fixture.canonical.length}">${fixture.html}</span>`:fixture.html}</p></body></html>`};packets=[];errors=[];
  const page=await browser.newPage({viewport:{width:900,height:600}});page.on('pageerror',error=>errors.push(String(error)));
  await page.exposeFunction('reviewReceive',raw=>packets.push({rawUnits:raw.length,rawBytes:Buffer.byteLength(raw),event:JSON.parse(raw)}));
  await page.addInitScript(()=>window.ReaderEvents={postMessage:raw=>window.reviewReceive(raw)});
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/dense/revision/${protocol===1?'host.html':'host2.html'}`);await page.waitForFunction(()=>window.readerHost);
  await page.evaluate(protocol=>readerHost.command({protocol,sessionId:'dense',revision:'revision',commandId:1,layoutEpoch:1,intentToken:'dense-open',intentCause:'Restore',type:'open',payload:{sectionCount:1,target:protocol===1?{section:0,blockId:'0',character:0}:{section:0,kind:'TEXT',offset:0},flow:'paginated',paginationBase:'dense-production-base',profileId:'test',geometry:{gap:'7%','margin-top':'16px','margin-bottom':'16px','margin-left':'16px','margin-right':'16px','max-column-count':2},beforeAuthor:'html,body,p{margin:0;padding:0}body{font:12px serif;line-height:1.8}rt{font-size:8px}',afterAuthor:''}}),protocol);
  await page.waitForFunction(()=>readerHost.diagnostics().events.some(e=>['ready','error'].includes(e.type)),{},{timeout:15000});
  await page.waitForTimeout(300);
  function decodeGroups(groups){return (groups??[]).flatMap(group=>{const bytes=Buffer.from(group.encoded,'base64');let at=0,previous=0;const integer=()=>{let n=0,shift=0,b;do{b=bytes[at++];n+=(b&127)*2**shift;shift+=7}while(b&128);return n};const ranges=[];while(at<bytes.length){const start=previous+integer(),end=start+integer();ranges.push({start:{section:group.section,kind:'TEXT',offset:start},end:{section:group.section,kind:'TEXT',offset:end}});previous=end}return ranges})}
  const relocation=packets.filter(x=>x.event.type==='relocated').at(-1),hostErrors=packets.filter(x=>x.event.type==='error').map(x=>x.event.message);
  const intervals=relocation?[...relocation.event.viewport.ranges.map(x=>({start:{...x.start,offset:x.start.offset??x.start.character},end:{...x.end,offset:x.end.offset??x.end.character}})),...decodeGroups(relocation.event.viewport.rangeGroups)]:[];
  const actual=await page.evaluate(async(intervals)=>{const p=document.getElementById('renderer'),entries=p.getContents(),doc=entries.find(x=>x.index===0)?.doc;const viewport=p.getBoundingClientRect(),frame=doc.defaultView.frameElement.getBoundingClientRect(),utility=await import('./viewport.js');const spans=utility.textIntervals(doc,viewport);let extractedUnits=0,visibleUnits=0,falseIncludedUnits=0,missingVisibleUnits=0,missing=[],offset=0;const w=doc.createTreeWalker(doc.body,NodeFilter.SHOW_TEXT);for(let n;(n=w.nextNode());){for(let at=0;at<n.length;at++){const r=doc.createRange();r.setStart(n,at);r.setEnd(n,at+1);const b=r.getBoundingClientRect();const included=spans.some(x=>x.node===n&&x.start<=at&&at<x.end);const packetIncluded=intervals.some(x=>x.start.offset<=offset+at&&offset+at<x.end.offset);if(packetIncluded!==included)throw new Error(`Packet membership differs at ${offset+at}`);if(included)extractedUnits++;if(b.width>.1&&b.height>.1&&b.right+frame.left>viewport.left&&b.left+frame.left<viewport.right&&b.bottom+frame.top>viewport.top&&b.top+frame.top<viewport.bottom){visibleUnits++;if(!included)missingVisibleUnits++;if(!included&&missing.length<12)missing.push({sourceOffset:offset+at,char:n.data[at],parent:n.parentElement.tagName,rect:{left:b.left,top:b.top,width:b.width,height:b.height}})}else if(included)falseIncludedUnits++;}offset+=n.length};return {extractedUnits,visibleUnits,falseIncludedUnits,missingVisibleUnits,missing,canonical:doc?.body?.textContent,textNodes:doc?(()=>{let count=0,w=doc.createTreeWalker(doc.body,NodeFilter.SHOW_TEXT);while(w.nextNode())count++;return count})():0,domInline:doc?.querySelectorAll('span,ruby,rt').length,visible:readerHost.diagnostics().anchorGeometry?.visible}},intervals);
  const row={name:fixture.name,protocol,inputSha:hash(active.document),sourceUnits:fixture.canonical.length,actualTextNodes:actual.textNodes,inlineElements:actual.domInline,visibleUnits:actual.visibleUnits,extractedUnits:actual.extractedUnits,falseIncludedUnits:actual.falseIncludedUnits,missingVisibleUnits:actual.missingVisibleUnits,firstMissingVisibleGlyphs:actual.missing,canonicalMatches:actual.canonical===fixture.canonical,ready:packets.some(x=>x.event.type==='ready'),maxRawUnits:Math.max(...packets.map(x=>x.rawUnits)),positionPackets:packets.filter(x=>x.event.type==='relocated').map(x=>({rawUnits:x.rawUnits,rawBytes:x.rawBytes,ranges:x.event.viewport?.ranges.length,compactGroups:x.event.viewport?.rangeGroups?.length??0,elements:x.event.viewport?.elements.length,points:x.event.viewport?.points.length})),hostErrors,pageErrors:errors};
  if(fixture.name==='600_hidden_inline_gaps')assert(relocation.event.viewport.rangeGroups.length>0);results.push(row);console.log(JSON.stringify({case:row}));await page.close();
 }
 console.log(JSON.stringify({sourceBindings:bindings,results,scope:'actual production host1 and host2; every real glyph and decoded packet range checked, desktop only; native typed consumer separate instrumentation'}));
 for(const row of results){assert(row.canonicalMatches);assert(row.ready);assert(row.positionPackets.length>0);assert(row.maxRawUnits<=65536);assert.equal(row.missingVisibleUnits,0);assert.equal(row.falseIncludedUnits,0);assert.deepEqual(row.hostErrors,[]);assert.deepEqual(row.pageErrors,[])}
 }finally {await browser.close();server.close()}
})().catch(error=>{console.error(error.stack);server.close();process.exitCode=1});
