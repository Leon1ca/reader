// Trusted gesture policy shared by all local document hosts. Coordinates are host CSS pixels.
export function installInput(doc,{active,flow,direction,zoom=()=>1,emit,doubleTap,imageTap=false}) {
    let down=null,selectionAt=-Infinity,timer=0,lastClick=-Infinity,hadSelection=false
    const selected=()=>doc.getSelection()?.isCollapsed===false
    doc.addEventListener('selectionchange',()=>{const now=selected();if(now||hadSelection){selectionAt=performance.now();clearTimeout(timer)}hadSelection=now},true)
    doc.addEventListener('pointerdown',e=>{if(e.isTrusted)down={x:e.clientX,y:e.clientY,id:e.pointerId,moved:false}},true)
    doc.addEventListener('pointermove',e=>{if(down && e.pointerId===down.id && Math.hypot(e.clientX-down.x,e.clientY-down.y)>8)down.moved=true},true)
    doc.addEventListener('pointercancel',()=>{down=null;clearTimeout(timer)},true)
    doc.addEventListener('click',e=>{
        if(!e.isTrusted || !active() || (e.target.closest?.('a')||(!imageTap&&e.target.closest?.('img'))) || selected() || performance.now()-selectionAt<300 || down?.moved)return
        const element=doc.defaultView.frameElement,frame=element?.getBoundingClientRect(),scale=frame?frame.width/element.clientWidth:1,x=e.clientX*scale+(frame?.left??0),width=globalThis.visualViewport?.width??innerWidth
        if(!Number.isFinite(x) || width<=0)return
        const region=flow()==='scrolled'||zoom()>1?'CENTER':x<width*.3?'LEFT':x>width*.7?'RIGHT':'CENTER'
        const send=()=>{if(active() && !selected() && performance.now()-selectionAt>=300)emit('tapRequested',{region,direction:direction(),trustedInput:true})}
        if(doubleTap) {
            clearTimeout(timer)
            if(e.detail>1 || performance.now()-lastClick<300){lastClick=-Infinity;doubleTap();return}
            lastClick=performance.now();timer=setTimeout(send,300)
        } else if(e.detail===1)send()
    },true)
}
// Native px are converted exactly once against this host's measured CSS viewport.
export function applyInsets(payload,fixedRenderer) {
    const n=payload.nativeInsets
    if(!n)return
    const width=globalThis.visualViewport?.width??innerWidth,ratio=n.width/width
    if(!Number.isFinite(ratio)||ratio<=0||['left','top','right','bottom'].some(k=>!Number.isInteger(n[k])||n[k]<0||n[k]>16384))throw Error('Invalid native viewport insets')
    const css=Object.fromEntries(['left','top','right','bottom'].map(k=>[k,n[k]/ratio]))
    if(fixedRenderer) {
        fixedRenderer.style.marginLeft=`${css.left}px`;fixedRenderer.style.marginTop=`${css.top}px`
        fixedRenderer.style.width=`calc(100% - ${css.left+css.right}px)`;fixedRenderer.style.height=`calc(100% - ${css.top+css.bottom}px)`
    } else {
        // The paginator reads these custom properties with parseFloat, not the
        // browser's CSS length resolver. Its geometry contract uses CSS pixels.
        const geometry={...payload.geometry}
        for(const side of ['left','top','right','bottom']) {
            const base=geometry[`margin-${side}`]??'0px'
            if(typeof base!=='string'||!/^\s*(?:\d+(?:\.\d*)?|\.\d+)px\s*$/.test(base))throw Error('Invalid reader pixel margin')
            const value=parseFloat(base)+css[side]
            if(!Number.isFinite(value)||value<0)throw Error('Invalid reader pixel margin')
            geometry[`margin-${side}`]=`${value}px`
        }
        payload.geometry=geometry
        // Continuous scrolling lets the text run behind the title and the page number (the scroll container spans the whole
        // grid, and horizontal scrolled flow ignores the top/bottom margins). Reserve that space inside each section instead,
        // so a chapter's first line starts below the title and its last line ends above the page number. The paginator already
        // publishes the margin it was given as --page-margin-top/bottom (including the insets added above); paged flow keeps
        // its own margins and is not touched.
        if(payload.flow==='scrolled')payload.afterAuthor=(payload.afterAuthor??'')+':root{padding-top:var(--page-margin-top)!important;padding-bottom:var(--page-margin-bottom)!important;}'
    }
}
