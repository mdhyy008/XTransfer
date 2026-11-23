import React from 'react'
import ReactDOM from 'react-dom/client'
import './assets/misans/MiSansVF.min.css'
import App from './App'
import DropPad from './components/DropPad'
import LogWindow from './components/LogWindow'

const isDropPad = window.location.hash === '#droppad'
const isLogs = window.location.hash === '#logs'

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    {isDropPad ? <DropPad /> : isLogs ? <LogWindow /> : <App />}
  </React.StrictMode>
)
