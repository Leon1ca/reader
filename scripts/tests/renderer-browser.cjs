/* Run with NODE_PATH pointing at Playwright and READER_CHROMIUM at a Chromium executable. */
const { chromium } = require('playwright')
const http = require('http'), fs = require('fs'), path = require('path'), assert = require('assert/strict')
const root = path.resolve(__dirname, '../../app/src/main/assets/reader')
const base = '/reader/test-session/test-revision/'
const requests = [], sectionLoads = [], session = { sessionId:'test-session', revision:'test-revision' }
const server = http.createServer((req,res) => {
  requests.push(req.url)
  if (!req.url.startsWith(base)) { res.writeHead(403); res.end(); return }
  const resource = req.url.slice(base.length)
  const match = /^sections\/(\d+)\.html$/.exec(resource)
  if (match && Number(match[1]) < 32768) {
    const index = Number(match[1]); sectionLoads.push(index)
    res.writeHead(200, {'Content-Type':'text/html; charset=utf-8','Content-Security-Policy':"default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src 'none'; frame-src 'none'; connect-src 'none'; form-action 'none'; base-uri 'none'"})
    res.end(`<html><head><meta charset="utf-8"></head><body>${Array.from({length:8},(_,i)=>`<p><span data-block="${index*8+i}" data-length="80">${('中文 English 123 '+ index + ':'+i+' ').padEnd(80,'字')}</span></p>`).join('')}<script>window.attacked=true;parent.ReaderEvents.postMessage('attacked')</script><script src="../host.js"></script><svg onload="parent.attacked=true"></svg><a href="javascript:parent.attacked=true" onclick="parent.attacked=true">evil</a></body></html>`)
    return
  }
  if (!['host.html','host.js','viewport.js','input.js','vendor/foliate/paginator.js'].includes(resource)) {res.writeHead(403);res.end();return}
  res.writeHead(200, {'Content-Type':resource.endsWith('.js')?'text/javascript':'text/html','Cache-Control':'no-store'})
  res.end(fs.readFileSync(path.join(root,resource)))
})
;(async()=>{
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
  const browser = await chromium.launch({executablePath:process.env.READER_CHROMIUM,headless:true})
  const page = await browser.newPage({viewport:{width:412,height:720}})
  page.on('pageerror',error=>console.error('PAGE ERROR',error))
  page.on('console',message=>{if(['error','warning'].includes(message.type()))console.error('BROWSER',message.text())})
  let commandId=0,epoch=0
  const messages=[]
  await page.exposeFunction('received',raw=>messages.push(JSON.parse(raw)))
  await page.addInitScript(()=>{window.ReaderEvents={postMessage:raw=>window.received(raw)}})
  try {
    await page.goto(`http://127.0.0.1:${server.address().port}${base}host.html`)
    await page.waitForFunction(()=>window.readerHost)
    const rules={flow:'paginated',profileId:'test',geometry:{gap:'7%','margin-top':'24px','margin-bottom':'24px','margin-left':'16px','margin-right':'16px','max-column-count':1},beforeAuthor:'html,body{margin:0;}body{font:20px serif;}p{line-height:1.6;margin:1em 0;}',afterAuthor:''}
    async function command(type,payload){
      const cmd={protocol:1,...session,layoutEpoch:++epoch,commandId:++commandId,type,payload}
      await page.evaluate(c=>readerHost.command(c),cmd)
      try {await page.waitForFunction(id=>readerHost.diagnostics().events.some(e=>['ready','error'].includes(e.type)&&e.commandId===id),commandId,{timeout:15000})}
      catch(error){console.error('DIAGNOSTICS',JSON.stringify(await page.evaluate(()=>readerHost.diagnostics())));throw error}
      const error=messages.find(e=>e.commandId===commandId&&e.type==='error'); assert.equal(error,undefined)
    }
    const target={section:0,blockId:'0',character:3}
    await page.evaluate(()=>{document.documentElement.style.height='0px'})
    assert.equal(await page.evaluate(()=>document.getElementById('renderer').getBoundingClientRect().height),0,'simulate a cached zero shell height before native measurement')
    await command('open',{...rules,sectionCount:32768,target})
    assert.equal(await page.evaluate(()=>Math.abs(document.getElementById('renderer').getBoundingClientRect().height-visualViewport.height)<2),true,'shell must use its measured CSS-pixel viewport')
    assert.equal(await page.evaluate(()=>{
      const sections=document.getElementById('renderer').sections
      return sections.length===32768 && sections[0].id==='0' && sections.at(-1).id==='32767' && sections[32768]===undefined &&
        (0 in sections) && (32767 in sections) && !(32768 in sections) && sections.some(s=>s.id==='0') &&
        sections.slice(0,2).map(s=>s.id).join(',')==='0,1'
    }),true,'lazy sections must preserve the public Array indexed/method contract')
    assert.equal((await page.evaluate(()=>readerHost.diagnostics())).target.character,3)
    assert.equal((await page.evaluate(()=>readerHost.diagnostics())).anchorGeometry.visible,true,'actual target glyph must be in viewport')
    const initialLoads=sectionLoads.length
    assert(initialLoads<10,'did not eagerly load full 20 MiB publication')
    await command('next',{})
    await page.waitForTimeout(150)
    assert(messages.some(e=>e.type==='relocated'&&e.reason!=='anchor'))
    await command('setRules',{...rules,flow:'scrolled',target:{section:0,blockId:'0',character:3}})
    let maxViews=0
    // Actual continuous container scrolling, not repeated goTo/section replacement.
    for(let i=0;i<250;i++){
      await page.evaluate(()=>{document.getElementById('renderer').containerPosition+=250})
      await page.waitForTimeout(40)
      const d=await page.evaluate(()=>readerHost.diagnostics());maxViews=Math.max(maxViews,d.activeViews)
      assert(d.activeViews<=8);assert(d.contents.reduce((s,c)=>s+c.textLength,0)<12000)
      if(Number(d.target?.blockId)>=24*8)break
    }
    const advanced=await page.evaluate(()=>readerHost.diagnostics())
    assert(Number(advanced.target.blockId)>=24*8,'continuous scroll must cross 24 sections')
    const anchor=advanced.target
    await command('setRules',{...rules,flow:'scrolled',beforeAuthor:rules.beforeAuthor.replace('20px','26px'),target:anchor})
    assert.deepEqual((await page.evaluate(()=>readerHost.diagnostics())).target,anchor)
    assert.equal((await page.evaluate(()=>readerHost.diagnostics())).anchorGeometry.visible,true,'reflow must retain actual source glyph visibility')
    const invalidations=await page.evaluate(()=>readerHost.diagnostics().events.filter(e=>e.type==='layoutInvalidated').length)
    await page.setViewportSize({width:720,height:412})
    await page.waitForFunction(count=>readerHost.diagnostics().events.filter(e=>e.type==='layoutInvalidated').length>count,invalidations)
    // WebReaderAdapter handles layoutInvalidated by issuing setRules at the saved locator. Replay that native half of
    // the production protocol here; resizing an isolated host without its owner is not the application's rotate path.
    await command('setRules',{...rules,flow:'scrolled',beforeAuthor:rules.beforeAuthor.replace('20px','26px'),target:anchor})
    assert.deepEqual((await page.evaluate(()=>readerHost.diagnostics())).target,anchor,'resize must retain the same source locator')
    assert.equal((await page.evaluate(()=>readerHost.diagnostics())).anchorGeometry.visible,true,'resize must retain actual source glyph visibility')
    assert.equal((await page.evaluate(()=>readerHost.diagnostics())).contents.some(c=>c.scriptExecuted),false)
    assert.equal(await page.evaluate(()=>window.attacked),undefined)
    const before=Number(anchor.blockId)
    for(let i=0;i<150;i++){
      await page.evaluate(()=>{document.getElementById('renderer').containerPosition-=250})
      await page.waitForTimeout(40)
      const d=await page.evaluate(()=>readerHost.diagnostics());assert(d.activeViews<=8)
      if(Number(d.target?.blockId)<before-48)break
    }
    assert(Number((await page.evaluate(()=>readerHost.diagnostics())).target.blockId)<before-48,'evicted previous sections must reload on backward scroll')
    const flowAnchor=(await page.evaluate(()=>readerHost.diagnostics())).target
    await page.evaluate(()=>{
      globalThis.releasedRenders=[]
      globalThis.watchReaderRelease=()=>{
        const engine=document.getElementById('renderer'),render=engine.render,destroy=engine.destroy
        const record={destroyed:false,renders:0};releasedRenders.push(record)
        engine.render=function(...args){if(record.destroyed)record.renders++;return render.apply(this,args)}
        engine.destroy=function(...args){const result=destroy.apply(this,args);record.destroyed=true;return result}
      }
      watchReaderRelease()
    })
    await command('setRules',{...rules,flow:'paginated',beforeAuthor:rules.beforeAuthor.replace('20px','22.5px'),target:flowAnchor})
    await page.waitForTimeout(700)
    assert((await page.evaluate(()=>readerHost.diagnostics())).anchorGeometry.visible,'late fill after scrolled→paginated must retain the source glyph')
    assert.deepEqual((await page.evaluate(()=>readerHost.diagnostics())).target,flowAnchor,'flow reflow must not persist a different page-start source')
    assert.equal(await page.evaluate(()=>releasedRenders[0].renders),0,'replaced paginator must disconnect its container observer')
    // New default permits two columns on a wide viewport. Narrow phones remain
    // one column, and both topology changes retain the exact source glyph.
    const autoRules={...rules,geometry:{...rules.geometry,'max-column-count':2},beforeAuthor:rules.beforeAuthor.replace('line-height:1.6;margin:1em 0','line-height:1.4;margin:0.6em 0;text-align:left;text-indent:0')}
    await page.setViewportSize({width:900,height:720})
    await command('setRules',{...autoRules,target:flowAnchor})
    const columns=()=>page.evaluate(()=>Number(document.getElementById('renderer').shadowRoot.getElementById('top').style.getPropertyValue('--_column-count')))
    assert.equal(await columns(),2,'wide TXT default must display two columns')
    assert.deepEqual((await page.evaluate(()=>readerHost.diagnostics())).target,flowAnchor)
    assert((await page.evaluate(()=>readerHost.diagnostics())).anchorGeometry.visible)
    await page.setViewportSize({width:393,height:829})
    await command('setRules',{...autoRules,target:flowAnchor})
    assert.equal(await columns(),1,'a narrow phone must remain one column')
    assert.deepEqual((await page.evaluate(()=>readerHost.diagnostics())).target,flowAnchor)
    assert((await page.evaluate(()=>readerHost.diagnostics())).anchorGeometry.visible)
    await page.evaluate(()=>{watchReaderRelease();readerHost.close()})
    const count=messages.length
    await page.setViewportSize({width:412,height:720});await page.waitForTimeout(300)
    assert.equal(messages.length,count)
    assert.equal(await page.evaluate(()=>releasedRenders.every(record=>record.destroyed&&record.renders===0)),true,'closed paginators must not render after container resize')
    console.log(JSON.stringify({ok:true,engine:'foliate341dc63',publicationCharacters:32768*8*80,maxViews,initialLoads,sectionLoads:sectionLoads.length,lastForwardAnchor:anchor,requests:requests.length}))
  } finally {await browser.close();server.close()}
})().catch(error=>{console.error(error);server.close();process.exitCode=1})
