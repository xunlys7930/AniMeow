import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { PopoutWindowHost } from './components/PopoutWindowHost'
import { StudioDialogProvider } from './components/StudioDialog'
import { parsePopoutRouteFromLocation } from './utils/popout'
import './styles.css'

const popoutRoute = parsePopoutRouteFromLocation()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <StudioDialogProvider>
      {popoutRoute ? <PopoutWindowHost route={popoutRoute} /> : <App />}
    </StudioDialogProvider>
  </StrictMode>
)
