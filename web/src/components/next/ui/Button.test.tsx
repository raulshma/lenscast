// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render } from '@solidjs/testing-library'
import Button from './Button'
import IconButton from './IconButton'

afterEach(cleanup)

// The kit's tooltip contract: the styled bubble (data-tooltip) is the only
// tooltip a beta control carries — never the native title, which the
// stylesheet primitive (styles/next/tooltip.css) knows nothing about.
describe('Button', () => {
  it('speaks data-tooltip and never a native title', () => {
    const { container } = render(() => <Button tooltip="Do the thing">Do</Button>)
    const button = container.querySelector('button')!
    expect(button.getAttribute('data-tooltip')).toBe('Do the thing')
    expect(button.hasAttribute('title')).toBe(false)
  })

  it('omits the bubble when no tooltip is given', () => {
    const { container } = render(() => <Button>Plain</Button>)
    const button = container.querySelector('button')!
    expect(button.hasAttribute('data-tooltip')).toBe(false)
    expect(button.hasAttribute('title')).toBe(false)
  })

  it('re-anchors the bubble through tooltipEdge', () => {
    const { container } = render(() => <Button tooltip="Down here" tooltipEdge="below">Do</Button>)
    expect(container.querySelector('button')!.getAttribute('data-tooltip-edge')).toBe('below')
  })
})

describe('IconButton', () => {
  it('defaults the bubble to its required label — icon-only buttons must say what they do', () => {
    const { container } = render(() => <IconButton label="Zoom in" />)
    const button = container.querySelector('button')!
    expect(button.getAttribute('data-tooltip')).toBe('Zoom in')
    expect(button.getAttribute('aria-label')).toBe('Zoom in')
    expect(button.hasAttribute('title')).toBe(false)
  })

  it('lets an explicit tooltip override the label in the bubble, not the name', () => {
    const { container } = render(() => <IconButton label="Zoom in" tooltip="Admin access required" disabled />)
    const button = container.querySelector('button')!
    expect(button.getAttribute('data-tooltip')).toBe('Admin access required')
    expect(button.getAttribute('aria-label')).toBe('Zoom in')
    expect(button.hasAttribute('title')).toBe(false)
  })
})
