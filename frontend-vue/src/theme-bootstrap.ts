import { applyTheme, readThemePreference } from './theme';

// index.html 里本模块排在 main.ts 之前：属性在样式生效前就位，首帧即正确主题。
// CSP 的 script-src 'self' 禁内联，所以不能把这段逻辑写成 index.html 里的 <script>。
applyTheme(readThemePreference());
