import {textIntervals,mergeIntervals,visibleFrame,fontGeometry,compactSignature} from './viewport.js'
// Trusted fixed-layout protocol-2 host. Content frames are inert and receive no native port.
const nativeFeatures=Array.prototype.at && Array.prototype.findLastIndex && globalThis.ResizeObserver && globalThis.CSSStyleSheet?.prototype.replaceSync
const base=new URL('./',location.href),identity={
    sessionId:base.pathname.split('/')[2],revision:decodeURIComponent(base.pathname.split('/')[3])
}
let renderer=document.getElementById('renderer'),closed=false,opening=true,commandId=0,epoch=0,latest=0,deadline=0,phase='boot',intentToken='',intentCause='Restore',userToken='',userUntil=0,chain=Promise.resolve(),descriptor,rules,lastTarget,reportTimer
const stabilitySamples=[],events=[],superseded=Symbol('superseded'),validated=new WeakSet(),fontWarned=new WeakSet(),pointers=new Map()
let paginationBase=''
let gesture,zooming=false,zoom=1,lastTap=0,viewportW=0,viewportH=0,gestureTimer
function syncViewport(){
    const w=visualViewport?.width??innerWidth,h=visualViewport?.height??innerHeight;
    if(closed||!Number.isFinite(w)||!Number.isFinite(h)||w<=0||h<=0)return false;
    const changed=Math.abs(w-viewportW)>.01||Math.abs(h-viewportH)>.01;
    viewportW=w;
    viewportH=h;
    document.documentElement.style.height=`${h}px`;
    return changed
}
syncViewport()
function emit(type,payload={
}){
    if(type==='relocated'){try{payload.viewport=readingViewport()}catch(error){emit('error',{message:String(error.message)});return}}
    if(closed)return;
    const value={
        protocol:2,...identity,layoutEpoch:epoch,commandId,intentToken,intentCause,type,...payload
    };
    if(events.length===32)events.shift();
    events.push(value);
    globalThis.ReaderEvents?.postMessage(JSON.stringify(value))
}
function bounded(promise,label){
    phase=label;
    const id=commandId;
    return new Promise((resolve,reject)=>{
        const timer=setInterval(()=>{
            if(closed||id!==latest){
                clearInterval(timer);
                reject(superseded)
            }else if(performance.now()>=deadline){
                clearInterval(timer);
                reject(new Error(`Fixed page operation timed out: ${label}`))
            }
        },40);
        Promise.resolve(promise).then(x=>{
            clearInterval(timer);
            resolve(x)
        },e=>{
            clearInterval(timer);
            reject(e)
        })
    })
}
const tick=()=>new Promise(r=>setTimeout(r,40))
// WebView scroll offsets are quantized to physical pixels. A requested point
// halfway between two pixels can alternate after float32 DOM readback; writing
// it again never improves the reachable position and keeps layout unsettled.
function restoreScroll(property,delta){
    const ratio=Number.isFinite(devicePixelRatio)&&devicePixelRatio>0?devicePixelRatio:1;
    const tolerance=Math.min(.5,.501/ratio); // Half a physical pixel + 1/1000px readback error.
    if(Math.abs(delta)>tolerance)renderer[property]+=delta
}
function frames(){
    return renderer.getContents?.().filter(x=>x.doc?.readyState==='complete'&&x.doc.body?.hasAttribute('data-reader-canonical-sha'))??[]
}
function boxes(){
    const viewport=renderer.getBoundingClientRect();
    return frames().map(({
        index,doc
    })=>({
        index,doc,rect:doc.defaultView.frameElement.getBoundingClientRect()
    })).filter(x=>x.rect.width>.1&&x.rect.height>.1&&x.rect.bottom>viewport.top+.1&&x.rect.top<viewport.bottom-.1&&x.rect.right>viewport.left+.1&&x.rect.left<viewport.right-.1)
}
function position(){
    const viewport=renderer.getBoundingClientRect(),visible=boxes().sort((a,b)=>a.index-b.index),first=visible[0];
    if(!first)return null;
    return {
        section:first.index,kind:'PAGE',x:Math.max(0,Math.min(1,(viewport.left-first.rect.left)/first.rect.width)),y:Math.max(0,Math.min(1,(viewport.top-first.rect.top)/first.rect.height))
    }
}
function endState() {
    const visible=boxes(),last=visible.find(page=>page.index===descriptor?.pages.length-1),view=renderer.getBoundingClientRect()
    return {atStart:visible.some(page=>page.index===0&&page.rect.top>=view.top-.5),atEnd:Boolean(last)}
}
function readingViewport() {
    const visible=boxes().sort((a,b)=>a.index-b.index),current=visible[0]?.index
    const rect=renderer.getBoundingClientRect()
    return {base:paginationBase,signature:compactSignature([rect.width,rect.height,renderer.getAttribute('flow'),renderer.getAttribute('zoom')]),currentSection:current,
        samples:visible.map(x=>({section:x.index,page:1,pages:1,estimated:false,signature:compactSignature(fontGeometry([x.doc]))})),ranges:[],elements:[],points:[],visiblePages:visible.map(x=>x.index),atEnd:visible.some(x=>x.index===descriptor.pages.length-1)}
}
function geometry(target){
    const entry=boxes().find(x=>x.index===target?.section);
    if(!entry)return {
        visible:false
    };
    const view=renderer.getBoundingClientRect(),point={
        left:entry.rect.left+target.x*entry.rect.width,top:entry.rect.top+target.y*entry.rect.height
    };
    return {
        ...point,width:entry.rect.width,height:entry.rect.height,visible:point.left>=view.left-.5&&point.left<=view.right+.5&&point.top>=view.top-.5&&point.top<=view.bottom+.5
    }
}
async function validate(doc){
    if(validated.has(doc))return;
    const walker=doc.createTreeWalker(doc.body,NodeFilter.SHOW_TEXT),parts=[];
    let node,size=0;
    while((node=walker.nextNode())){
        parts.push(node.data);
        size+=node.length
    }if(size!==Number(doc.body.dataset.readerCanonicalLength))throw new Error(`Fixed canonical length mismatch ${size}/${doc.body.dataset.readerCanonicalLength}`);
    const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(parts.join(''))),sha=[...new Uint8Array(digest)].map(x=>x.toString(16).padStart(2,'0')).join('');
    if(sha!==doc.body.dataset.readerCanonicalSha)throw new Error('Fixed canonical hash mismatch');
    validated.add(doc)
}
async function settled(target,restore=true){
    let signature,count=0;
    while(!closed&&performance.now()<deadline){
        if(commandId!==latest)throw superseded;
        syncViewport();
        const contents=renderer.getContents();
        if(!contents.length||contents.some(x=>x.doc?.readyState!=='complete'||!x.doc.body?.hasAttribute('data-reader-canonical-sha'))){
            await tick();
            continue
        }if(renderer.frameCount>4)throw new Error('Fixed frame budget exceeded');
        await bounded(Promise.all(contents.map(x=>validate(x.doc))),'canonical');
        await bounded(Promise.all(contents.map(x=>x.doc.fonts.ready)),'fonts');
        const listeners=new AbortController();
        try{
            await bounded(Promise.all(contents.flatMap(({
                doc
            })=>[...doc.images].map(img=>img.complete?Promise.resolve():new Promise((resolve,reject)=>{
                img.addEventListener('load',resolve,{
                    once:true,signal:listeners.signal
                });
                img.addEventListener('error',()=>reject(new Error('Fixed image failed')),{
                    once:true,signal:listeners.signal
                })
            })))),'images')
        }finally{
            listeners.abort()
        }if(contents.some(x=>[...x.doc.images].some(img=>!img.naturalWidth)))throw new Error('Fixed image failed')
        let correction={x:0,y:0};
        if(restore&&target){
            const entry=contents.find(x=>x.index===target.section);
            if(!entry){
                await tick();
                continue
            }const rect=entry.doc.defaultView.frameElement.getBoundingClientRect(),view=renderer.getBoundingClientRect();
            correction={x:rect.left-view.left+target.x*rect.width,y:rect.top-view.top+target.y*rect.height};
            restoreScroll('scrollTop',correction.y);
            restoreScroll('scrollLeft',correction.x);
            // The engine may render again after a late ResizeObserver callback.
            // Maintain the command's point only while opening, never after Ready.
        }
        // A browser may clip a spread's 1px overlap at an unscrollable edge. Preserve
        // the requested logical page, and expose the actual reachable point as nearby.
        if(target&&!geometry(target).visible) {
            const entry=boxes().find(x=>x.index===target.section),view=renderer.getBoundingClientRect()
            if(entry) {
                const maxX=Math.max(0,renderer.scrollWidth-renderer.clientWidth),maxY=Math.max(0,renderer.scrollHeight-renderer.clientHeight)
                const edgeX=Math.abs(renderer.scrollLeft)<.5||Math.abs(Math.abs(renderer.scrollLeft)-maxX)<.5
                const edgeY=renderer.scrollTop<.5||Math.abs(renderer.scrollTop-maxY)<.5
                if(edgeX)target.x=Math.max(target.x,Math.max(0,(view.left-entry.rect.left)/entry.rect.width))
                if(edgeY)target.y=Math.max(target.y,Math.max(0,(view.top-entry.rect.top)/entry.rect.height))
                if(geometry(target).visible)target.nearby=true
            }
        }
        await tick();
        const now=frames(),rectangles=now.map(x=>[x.index,x.doc.fonts.status,x.doc.defaultView.frameElement.getBoundingClientRect().toJSON()]),point=target?geometry(target):position(),next=JSON.stringify([rectangles,point]);
        count=signature===next?count+1:0;
        if(stabilitySamples.length===12)stabilitySamples.shift();
        stabilitySamples.push({commandId,correction,scroll:{x:renderer.scrollLeft,y:renderer.scrollTop},point,count,rectangles});
        signature=next;
        if(count>=3&&now.length===renderer.getContents().length&&now.every(x=>validated.has(x.doc)&&x.doc.fonts.status==='loaded')&&boxes().length&&(!target||geometry(target).visible)){
            return
        }
    }
    throw new Error('Fixed layout did not stabilize')
}
function userIntent(event){
    if(!event.isTrusted||opening||closed)return;
    userToken=crypto.randomUUID();
    userUntil=performance.now()+700;
    emit('userIntent',{
        userIntentToken:userToken,inputType:event.type
    })
}
function report(){
    if(opening||closed||zooming)return;
    const value=position();
    if(!value)return;
    if(lastTarget&&performance.now()>userUntil&&geometry(lastTarget).visible)return;
    lastTarget=value;
    clearTimeout(reportTimer);
    reportTimer=setTimeout(()=>emit('relocated',{
        target:value,reason:'scroll',intentCause:userToken?'UserNavigation':intentCause,trustedInput:Boolean(userToken),userIntentToken:userToken
    }),50)
}
function listen(engine){
    engine.addEventListener('relocate',()=>{
        if(engine===renderer)report()
    });
    engine.addEventListener('scroll',()=>{
        if(engine===renderer)report()
    });
    engine.addEventListener('load',({
        detail:{
            doc,index
        }
    })=>{
        if(engine!==renderer||closed)return;
        emit('sectionLoaded',{
            section:index,activeViews:engine.frameCount
        });
        doc.addEventListener('click',event=>{
            if(engine!==renderer || closed)return;
            const link=event.target.closest?.('a');
            if(!link)return;
            event.preventDefault();
            if(!opening&&event.isTrusted){
                if(link.dataset.readerExternal)emit('externalRequested',{
                    section:index,href:link.dataset.readerExternal
                });
                else if(link.dataset.readerHref)emit('linkRequested',{
                    section:index,href:link.dataset.readerHref
                })
            }
        },true);
        for(const name of ['wheel','touchmove','keydown'])doc.addEventListener(name,userIntent,{
            capture:true,passive:true
        });
        // Pointer policy is host-owned; prevent browser panning from cancelling
        // the inert frame's gesture stream before native page/zoom commands.
        doc.documentElement.style.touchAction='none';
        doc.body.style.touchAction='none';
        wirePointers(doc)
    })
}
async function rebuild(){
    const {
        FixedLayout
    }=await bounded(import('./vendor/foliate/fixed-layout.js'),'module');
    if(closed||commandId!==latest)throw superseded;
    const old=renderer;
    old.destroy?.();
    renderer=new FixedLayout();
    renderer.id='renderer';
    old.replaceWith(renderer);
    listen(renderer);
    renderer.setAttribute('zoom',rules.zoom);
    renderer.setAttribute('spread',rules.spread==='SINGLE'?'none':'auto');
    renderer.setAttribute('flow',rules.flow);
    document.body.style.background=rules.theme==='dark'?'#19201c':rules.theme==='paper'?'#f6f1e7':'#ffffff';
    zoom=Number(rules.zoom)||1;
    const sections=descriptor.pages.map(page=>({
        id:String(page.index),pageSpread:rules.spread==='SINGLE'||page.spread==='none'?'center':page.pageSpread||undefined,viewport:{
            width:page.width,height:page.height
        },load:()=>new URL(`sections/${page.index}.html`,base).href
    }));
    renderer.open({
        dir:descriptor.direction,sections,rendition:{
            spread:rules.spread==='SINGLE'?'none':'auto',viewport:{
                width:descriptor.pages[0].width,height:descriptor.pages[0].height
            }
        }
    })
}
async function execute(command){
    if(closed||command.commandId!==latest)return;
    commandId=command.commandId;
    epoch=command.layoutEpoch;
    intentToken=command.intentToken;
    intentCause=command.intentCause;
    opening=true;
    stabilitySamples.length=0;
    pointers.clear();
    gesture=null;zooming=false;
    clearTimeout(gestureTimer);
    userToken='';
    userUntil=0;
    clearTimeout(reportTimer);
    deadline=performance.now()+12000;
    try{
        if(!nativeFeatures)throw new Error('请更新系统 WebView 以使用固定版式内核');
        const p=command.payload;
        if(p.paginationBase)paginationBase=p.paginationBase;
        if(command.type==='open'){
            descriptor={
                pages:p.fixedPages,direction:p.direction,mixed:p.fixedMixed
            };
            if(!Array.isArray(descriptor.pages)||descriptor.pages.length!==p.sectionCount||descriptor.pages.length<1||descriptor.pages.length>20000)throw new Error('Invalid fixed pages');
            rules=p;
            lastTarget=p.target;
            await rebuild();
            await bounded(renderer.goTo({
                index:lastTarget.section
            }),'navigation');
            await settled(lastTarget)
        }else if(command.type==='goTo'||command.type==='setRules'){
            if(command.type==='setRules')rules={
                ...rules,...p
            };
            lastTarget=p.target;
            await rebuild();
            await bounded(renderer.goTo({
                index:lastTarget.section
            }),'navigation');
            await settled(lastTarget)
        }else if(command.type==='next'||command.type==='prev'){
            await bounded(renderer[command.type](),'page');
            await settled(null,false);
            lastTarget=position();
            if(!lastTarget)throw new Error('No visible fixed page')
        }else throw new Error('Unknown fixed command');
        if(commandId!==latest||closed)throw superseded;
        const nearby=lastTarget?.nearby;
        lastTarget=position()??lastTarget;
        if(nearby)lastTarget.nearby=true;
        opening=false;
        emit('ready',{
            profileId:'fxl-author-v1',activeViews:renderer.frameCount,nearby:Boolean(lastTarget.nearby||p.target?.nearby)
        });
        if(descriptor.mixed)emit('notice',{
            message:'此书包含不同版式的页面；当前统一按出版物的固定版式渲染。'
        });
        for(const {
            doc
        } of frames())if([...doc.fonts].some(face=>face.status==='error')&&!fontWarned.has(doc)){
            fontWarned.add(doc);
            emit('notice',{
                message:'包内字体加载失败，已使用备用字体。'
            })
        }emit('relocated',{
            target:lastTarget,...endState(),pagination:{current:lastTarget.section+1,total:descriptor.pages.length,estimated:false},reason:intentCause==='UserNavigation'?'page':'anchor',ack:commandId,trustedInput:false
        })
    }catch(e){
        if(e!==superseded&&!closed&&commandId===latest){
            opening=false;
            emit('error',{
                message:String(e.message??e).slice(0,300)
            })
        }
    }
}
globalThis.readerHost=Object.freeze({
    command(command){
        if(closed||command.protocol!==2||command.sessionId!==identity.sessionId||command.revision!==identity.revision||!Number.isInteger(command.commandId)||command.commandId<=latest)return;
        latest=command.commandId;
        if(opening)renderer.destroy?.();
        chain=chain.then(()=>execute(command))
    },close(){
        closed=true;
        clearTimeout(reportTimer);
        clearTimeout(gestureTimer);
        pointers.clear();
        renderer.destroy?.();
        renderer.remove()
    },diagnostics(){
        return {
            protocol:2,identity,commandId,layoutEpoch:epoch,phase,target:lastTarget,actualVisibleTarget:position(),anchorGeometry:geometry(lastTarget),activeViews:renderer.frameCount??0,allocatedFrames:renderer.frameCount??0,visiblePages:boxes().map(x=>x.index),...endState(),pagination:{current:(position()?.section??0)+1,total:descriptor?.pages.length??0,estimated:false},zoom:renderer.getAttribute('zoom'),flow:renderer.getAttribute('flow'),stabilitySamples:[...stabilitySamples],events:[...events],contents:frames().map(x=>({
                section:x.index,state:x.doc.readyState,canonicalLength:x.doc.body.dataset.readerCanonicalLength,validated:validated.has(x.doc),images:[...x.doc.images].map(img=>({
                    complete:img.complete,width:img.naturalWidth,height:img.naturalHeight
                })),fontFaces:[...x.doc.fonts].map(f=>({
                    family:f.family,status:f.status
                }))
            }))
        }
    }
})
for(const name of ['wheel','touchmove','keydown'])addEventListener(name,userIntent,{
    capture:true,passive:true
});
wirePointers(window)
// Gestures are handled only by the trusted shell. Inert content has no native port.
function wirePointers(target) {
    const coordinate=event=>{
        const doc=target.document??target,frame=doc.defaultView?.frameElement
        if(!frame)return {
            x:event.clientX,y:event.clientY
        }
        const rect=frame.getBoundingClientRect()
        return {
            x:rect.left+event.clientX*rect.width/frame.clientWidth,y:rect.top+event.clientY*rect.height/frame.clientHeight
        }
    }
    target.addEventListener('pointerdown',event=>{
        if(!event.isTrusted || opening || closed || event.button!==0)return
        userIntent(event)
        if(event.target?.setPointerCapture)event.target.setPointerCapture(event.pointerId)
        const point=coordinate(event);
        pointers.set(event.pointerId,point)
        const points=[...pointers.values()]
        if(points.length===2) {
            gesture={
                kind:'pinch',distance:Math.hypot(points[0].x-points[1].x,points[0].y-points[1].y),ratio:1,zoom:zoomValue()
            }
        } else if(points.length===1)gesture={
            kind:'pan',x:point.x,y:point.y,startX:point.x,startY:point.y,overflowX:renderer.scrollWidth>renderer.clientWidth+1,moved:false
        }
    },true)
    target.addEventListener('pointermove',event=>{
        if(!event.isTrusted || !pointers.has(event.pointerId) || opening || closed)return
        const point=coordinate(event);
        pointers.set(event.pointerId,point)
        if(gesture?.kind==='pinch' && pointers.size===2) {
            const points=[...pointers.values()],distance=Math.hypot(points[0].x-points[1].x,points[0].y-points[1].y)
            gesture.ratio=Math.max(1/gesture.zoom,Math.min(5/gesture.zoom,distance/Math.max(1,gesture.distance)))
            renderer.pinchZoom(gesture.ratio);
            event.preventDefault()
        } else if(gesture?.kind==='pan') {
            const dx=gesture.x-point.x,dy=gesture.y-point.y
            if(Math.abs(dx)+Math.abs(dy)>2)gesture.moved=true
            renderer.scrollBy(dx,dy);
            gesture.x=point.x;
            gesture.y=point.y
            userUntil=performance.now()+700;
            report();
            event.preventDefault()
        }
    },{
        capture:true,passive:false
    })
    const finish=event=>{
        if(!pointers.has(event.pointerId))return
        pointers.delete(event.pointerId)
        if(gesture?.kind==='pinch') {
            const value=Math.max(1,Math.min(5,gesture.zoom*gesture.ratio))
            renderer.pinchEnd();
            commitZoom(value)
        } else if(gesture?.kind==='pan' && gesture.moved && !gesture.overflowX && event.type==='pointerup') {
            const dx=gesture.x-gesture.startX,dy=gesture.y-gesture.startY
            if(Math.abs(dx)>48 && Math.abs(dx)>Math.abs(dy)*1.5)emit('pageRequested',{direction:(descriptor.direction==='rtl'?dx>0:dx<0)?'next':'prev',userIntentToken:userToken})
        } else if(gesture?.kind==='pan' && !gesture.moved && event.type==='pointerup') {
            const now=performance.now()
            if(now-lastTap<300){
                lastTap=0;
                commitZoom(zoomValue()>1?'fit-page':2)
            }else lastTap=now
        }
        gesture=null
    }
    target.addEventListener('pointerup',finish,true)
    target.addEventListener('pointercancel',finish,true)
}
function zoomValue() {
    const explicit=Number(renderer.getAttribute('zoom'))
    if(Number.isFinite(explicit) && explicit>0)return Math.max(1,Math.min(5,explicit))
    const page=boxes()[0],spec=descriptor?.pages[page?.index]
    return Math.max(1,Math.min(5,page&&spec?page.rect.width/spec.width:1))
}
function commitZoom(value) {
    if(closed || opening)return
    // Fit-page is a user control rather than a numeric gesture request.
    const numeric=typeof value==='number'?value:1
    zooming=true;userUntil=0;clearTimeout(reportTimer);
    renderer.setAttribute('zoom',String(value));
    zoom=numeric
    clearTimeout(gestureTimer)
    gestureTimer=setTimeout(()=>{
        if(closed||opening)return
        const target=position();
        if(!target)return
        lastTarget=target
        emit('zoomRequested',{
            zoom:typeof value==='number'?value:'fit-page',target,userIntentToken:userToken
        });zooming=false
    },160)
}
function resize(){
    if(syncViewport()&&!opening&&!closed)emit('layoutInvalidated')
}
addEventListener('resize',resize);
visualViewport?.addEventListener('resize',resize)
emit('booted')
