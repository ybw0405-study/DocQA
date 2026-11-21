/*
 * 运行时应用配置（CT-7 / CT-8）。
 *
 * 本文件由 Vite 原样拷进 dist 根目录，部署时由托管层按环境覆盖内容；它不是构建期常量，
 * 因此换桶名、换挂载前缀都无需重新构建前端。托管层必须对本文件下发
 * Cache-Control: no-store（见 nginx.conf.example），否则旧值会被浏览器缓存。
 *
 * 严禁在此写入任何密钥、令牌或用户数据：桶名与 API 前缀都不是机密，
 * 但这份文件对浏览器完全可读。
 */
window.__APP_CONFIG__ = {
  apiBase: '/api/v1',
  storageBucket: 'docqa-documents',
};
