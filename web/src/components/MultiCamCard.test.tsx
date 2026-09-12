// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@solidjs/testing-library'
import MultiCamCard from './MultiCamCard'

// Component smoke tests (jsdom): the card's empty state and its action row.
afterEach(() => cleanup())

describe('MultiCamCard', () => {
  it('renders the add-camera form and empty camera list', () => {
    render(() => <MultiCamCard />)
    expect(screen.getByText('Cameras')).toBeTruthy()
    expect(screen.getByText('0 cameras')).toBeTruthy()
    expect(screen.getByPlaceholderText('http://192.168.1.55:8080')).toBeTruthy()
    expect(screen.getByText('Add camera')).toBeTruthy()
  })

  it('disables the global actions while no camera is configured', () => {
    render(() => <MultiCamCard />)
    const captureAll = screen.getByText('Capture all').closest('button') as HTMLButtonElement
    const liveAll = screen.getByText('Live all').closest('button') as HTMLButtonElement
    expect(captureAll.disabled).toBe(true)
    expect(liveAll.disabled).toBe(true)
  })

  it('rejects an invalid URL with a visible error, keeping the entry out of the list', async () => {
    const { fireEvent } = await import('@solidjs/testing-library')
    render(() => <MultiCamCard />)
    const urlInput = screen.getByPlaceholderText('http://192.168.1.55:8080') as HTMLInputElement
    fireEvent.input(urlInput, { target: { value: 'ftp://not-a-camera' } })
    fireEvent.submit(screen.getByText('Add camera').closest('form')!)
    expect(screen.getByRole('alert').textContent).toContain('valid http(s) URL')
    expect(screen.getByText('0 cameras')).toBeTruthy()
  })
})
