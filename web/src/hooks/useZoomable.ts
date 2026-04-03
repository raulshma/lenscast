import { createSignal, onCleanup, onMount } from 'solid-js'

interface UseZoomableOptions {
  minScale?: number
  maxScale?: number
  initialScale?: number
  wheelZoomFactor?: number
}

interface UseZoomableReturn {
  scale: () => number
  zoomPercent: () => number
  translateX: () => number
  translateY: () => number
  isZoomed: () => boolean
  containerRef: (el: HTMLElement | null) => void
  resetZoom: () => void
}

export function useZoomable(options: UseZoomableOptions = {}): UseZoomableReturn {
  const {
    minScale = 1,
    maxScale = 10,
    initialScale = 1,
    wheelZoomFactor = 0.1,
  } = options

  const [scale, setScale] = createSignal(initialScale)
  const [translateX, setTranslateX] = createSignal(0)
  const [translateY, setTranslateY] = createSignal(0)
  const isZoomed = () => scale() > 1

  let container: HTMLElement | null = null
  let isPanning = false
  let startX = 0
  let startY = 0
  let startTranslateX = 0
  let startTranslateY = 0
  let initialPinchDistance = 0
  let initialPinchScale = 1

  function getContainerBounds() {
    if (!container) return { width: 0, height: 0 }
    return {
      width: container.clientWidth,
      height: container.clientHeight,
    }
  }

  function clampTranslate(tx: number, ty: number, currentScale: number) {
    const { width, height } = getContainerBounds()
    if (currentScale <= 1) return { x: 0, y: 0 }

    const maxTx = (width * (currentScale - 1)) / 2
    const maxTy = (height * (currentScale - 1)) / 2

    return {
      x: Math.max(-maxTx, Math.min(maxTx, tx)),
      y: Math.max(-maxTy, Math.min(maxTy, ty)),
    }
  }

  function handleWheel(e: WheelEvent) {
    e.preventDefault()
    if (!container) return

    const rect = container.getBoundingClientRect()
    const mouseX = e.clientX - rect.left - rect.width / 2
    const mouseY = e.clientY - rect.top - rect.height / 2

    const delta = e.deltaY > 0 ? -wheelZoomFactor : wheelZoomFactor
    const currentScale = scale()
    const newScale = Math.max(minScale, Math.min(maxScale, currentScale + delta * currentScale))
    const scaleRatio = newScale / currentScale

    const newTx = mouseX - (mouseX - translateX()) * scaleRatio
    const newTy = mouseY - (mouseY - translateY()) * scaleRatio
    const clamped = clampTranslate(newTx, newTy, newScale)

    setScale(newScale)
    setTranslateX(clamped.x)
    setTranslateY(clamped.y)
  }

  function getTouchDistance(touches: TouchList) {
    const dx = touches[0].clientX - touches[1].clientX
    const dy = touches[0].clientY - touches[1].clientY
    return Math.sqrt(dx * dx + dy * dy)
  }

  function getTouchCenter(touches: TouchList) {
    return {
      x: (touches[0].clientX + touches[1].clientX) / 2,
      y: (touches[0].clientY + touches[1].clientY) / 2,
    }
  }

  function handleTouchStart(e: TouchEvent) {
    if (e.touches.length === 2) {
      e.preventDefault()
      initialPinchDistance = getTouchDistance(e.touches)
      initialPinchScale = scale()
    } else if (e.touches.length === 1 && isZoomed()) {
      isPanning = true
      startX = e.touches[0].clientX
      startY = e.touches[0].clientY
      startTranslateX = translateX()
      startTranslateY = translateY()
    }
  }

  function handleTouchMove(e: TouchEvent) {
    if (e.touches.length === 2) {
      e.preventDefault()
      const currentDistance = getTouchDistance(e.touches)
      const scaleRatio = currentDistance / initialPinchDistance
      const newScale = Math.max(minScale, Math.min(maxScale, initialPinchScale * scaleRatio))

      const rect = container!.getBoundingClientRect()
      const center = getTouchCenter(e.touches)
      const centerX = center.x - rect.left - rect.width / 2
      const centerY = center.y - rect.top - rect.height / 2

      const currentScale = scale()
      const zoomRatio = newScale / currentScale
      const newTx = centerX - (centerX - translateX()) * zoomRatio
      const newTy = centerY - (centerY - translateY()) * zoomRatio
      const clamped = clampTranslate(newTx, newTy, newScale)

      setScale(newScale)
      setTranslateX(clamped.x)
      setTranslateY(clamped.y)
    } else if (e.touches.length === 1 && isPanning) {
      e.preventDefault()
      const dx = e.touches[0].clientX - startX
      const dy = e.touches[0].clientY - startY
      const clamped = clampTranslate(startTranslateX + dx, startTranslateY + dy, scale())
      setTranslateX(clamped.x)
      setTranslateY(clamped.y)
    }
  }

  function handleTouchEnd(e: TouchEvent) {
    if (e.touches.length < 2) {
      initialPinchDistance = 0
    }
    if (e.touches.length === 0) {
      isPanning = false
    }
  }

  function handleMouseDown(e: MouseEvent) {
    if (e.button === 0 && isZoomed()) {
      isPanning = true
      startX = e.clientX
      startY = e.clientY
      startTranslateX = translateX()
      startTranslateY = translateY()
      e.preventDefault()
    }
  }

  function handleMouseMove(e: MouseEvent) {
    if (isPanning) {
      const dx = e.clientX - startX
      const dy = e.clientY - startY
      const clamped = clampTranslate(startTranslateX + dx, startTranslateY + dy, scale())
      setTranslateX(clamped.x)
      setTranslateY(clamped.y)
    }
  }

  function handleMouseUp() {
    isPanning = false
  }

  function resetZoom() {
    setScale(1)
    setTranslateX(0)
    setTranslateY(0)
  }

  function handleDblClick(e: MouseEvent) {
    e.preventDefault()
    if (isZoomed()) {
      resetZoom()
    } else {
      if (!container) return
      const rect = container.getBoundingClientRect()
      const clickX = e.clientX - rect.left - rect.width / 2
      const clickY = e.clientY - rect.top - rect.height / 2
      const newScale = 2.5
      const clamped = clampTranslate(clickX * (newScale - 1), clickY * (newScale - 1), newScale)
      setScale(newScale)
      setTranslateX(clamped.x)
      setTranslateY(clamped.y)
    }
  }

  onMount(() => {
    if (!container) return
    container.addEventListener('wheel', handleWheel, { passive: false })
    container.addEventListener('touchstart', handleTouchStart, { passive: false })
    container.addEventListener('touchmove', handleTouchMove, { passive: false })
    container.addEventListener('touchend', handleTouchEnd)
    container.addEventListener('mousedown', handleMouseDown)
    container.addEventListener('mousemove', handleMouseMove)
    container.addEventListener('mouseup', handleMouseUp)
    container.addEventListener('dblclick', handleDblClick)
  })

  onCleanup(() => {
    if (!container) return
    container.removeEventListener('wheel', handleWheel)
    container.removeEventListener('touchstart', handleTouchStart)
    container.removeEventListener('touchmove', handleTouchMove)
    container.removeEventListener('touchend', handleTouchEnd)
    container.removeEventListener('mousedown', handleMouseDown)
    container.removeEventListener('mousemove', handleMouseMove)
    container.removeEventListener('mouseup', handleMouseUp)
    container.removeEventListener('dblclick', handleDblClick)
  })

  const containerRef = (el: HTMLElement | null) => {
    container = el
  }

  const zoomPercent = () => Math.round(scale() * 100)

  return {
    scale,
    zoomPercent,
    translateX,
    translateY,
    isZoomed,
    containerRef,
    resetZoom,
  }
}
