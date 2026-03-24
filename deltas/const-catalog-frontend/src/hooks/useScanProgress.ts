import { useState, useCallback, useRef } from 'react'
import type { ScanProgressEvent } from '../types'
import { getScanStreamUrl } from '../services/api'

interface UseScanProgressReturn {
  events: ScanProgressEvent[]
  isConnected: boolean
  isComplete: boolean
  latestFiles: string[]
  currentEvent: ScanProgressEvent | null
  startStreaming: (projectPath: string) => void
  stopStreaming: () => void
}

/*
 * Custom hook for consuming Server-Sent Events from the scan progress endpoint.
 * Manages the EventSource lifecycle and maintains a rolling window of recent files.
 */
export function useScanProgress(): UseScanProgressReturn {
  const [events, setEvents] = useState<ScanProgressEvent[]>([])
  const [isConnected, setIsConnected] = useState(false)
  const [isComplete, setIsComplete] = useState(false)
  const [currentEvent, setCurrentEvent] = useState<ScanProgressEvent | null>(null)
  const eventSourceRef = useRef<EventSource | null>(null)

  const stopStreaming = useCallback(() => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close()
      eventSourceRef.current = null
    }
    setIsConnected(false)
  }, [])

  const startStreaming = useCallback((projectPath: string) => {
    // Close any existing connection
    stopStreaming()
    setEvents([])
    setCurrentEvent(null)
    setIsComplete(false)

    const url = getScanStreamUrl(projectPath)
    const eventSource = new EventSource(url)
    eventSourceRef.current = eventSource

    eventSource.onopen = () => {
      setIsConnected(true)
    }

    eventSource.addEventListener('progress', (event: MessageEvent) => {
      try {
        const data: ScanProgressEvent = JSON.parse(event.data)
        setCurrentEvent(data)
        setEvents((prev) => [...prev, data])
      } catch {
        // Ignore parse errors
      }
    })

    eventSource.addEventListener('complete', (event: MessageEvent) => {
      try {
        const data: ScanProgressEvent = JSON.parse(event.data)
        setCurrentEvent(data)
        setEvents((prev) => [...prev, data])
        setIsComplete(true)
      } catch {
        // Ignore parse errors
      }
      stopStreaming()
    })

    eventSource.addEventListener('error', () => {
      stopStreaming()
    })

    eventSource.onerror = () => {
      stopStreaming()
    }
  }, [stopStreaming])

  // Keep a rolling window of the last 25 file names
  const latestFiles = events
    .filter((e) => e.fileName)
    .slice(-25)
    .map((e) => e.fileName)

  return {
    events,
    isConnected,
    isComplete,
    latestFiles,
    currentEvent,
    startStreaming,
    stopStreaming,
  }
}
