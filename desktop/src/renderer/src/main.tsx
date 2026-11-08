import React from 'react'
import ReactDOM from 'react-dom/client'
import './assets/misans/MiSansVF.min.css'
import App from './App'
import DropPad from './components/DropPad'

const isDropPad = window.location.hash === '#droppad'

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>{isDropPad ? <DropPad /> : <App />}</React.StrictMode>
)
