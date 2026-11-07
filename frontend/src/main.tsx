import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './styles/theme.css';
import './styles/polish.css';
import './styles/layout.css';

const container = document.getElementById('root');
if (!container) {
  throw new Error('找不到 #root 挂载节点');
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
