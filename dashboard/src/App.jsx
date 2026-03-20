import { useState, useEffect, useCallback } from 'react'
import './App.css'

const API = '/api/manager'

function App() {
  const [status, setStatus] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)

  const fetchStatus = useCallback(async () => {
    try {
      const res = await fetch(`${API}/status`)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      setStatus(await res.json())
      setError(null)
    } catch (e) {
      setError('Cannot reach API: ' + e.message)
    }
  }, [])

  useEffect(() => {
    fetchStatus()
    const id = setInterval(fetchStatus, 2000)
    return () => clearInterval(id)
  }, [fetchStatus])

  const post = async (action) => {
    setLoading(true)
    try {
      await fetch(`${API}/${action}`, { method: 'POST' })
      setTimeout(fetchStatus, 1500)
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  const running = status?.managerRunning
  const workers = status?.workers ?? []
  const target = status?.targetWorkers ?? '?'

  return (
    <div className="dashboard">
      <h1>Worker Manager Dashboard</h1>

      {error && <div className="error">{error}</div>}

      <div className="status-bar">
        <span className={`indicator ${running ? 'up' : 'down'}`} />
        <span className="status-text">
          Manager: <strong>{running ? 'Running' : 'Stopped'}</strong>
        </span>
        <span className="target">Target: {target} workers</span>
      </div>

      <div className="controls">
        <button
          onClick={() => post('start')}
          disabled={loading || running}
          className="btn start"
        >
          Start
        </button>
        <button
          onClick={() => post('stop')}
          disabled={loading || !running}
          className="btn stop"
        >
          Stop
        </button>
      </div>

      <h2>Workers ({workers.length}/{target})</h2>

      {workers.length === 0 ? (
        <p className="empty">No workers running.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>ZNode</th>
              <th>Worker ID</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {workers.map((w) => (
              <tr key={w.znode}>
                <td className="mono">{w.znode}</td>
                <td className="mono">{w.workerId}</td>
                <td><span className="badge up">UP</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

export default App
