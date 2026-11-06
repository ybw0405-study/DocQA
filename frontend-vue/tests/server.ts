import { setupServer } from 'msw/node';
import {
  adminHandlers,
  authHandlers,
  conversationHandlers,
  documentHandlers,
  notificationHandlers,
  reminderHandlers,
  scheduleHandlers,
  usersHandlers,
} from './fixtures';

export const server = setupServer(
  ...authHandlers,
  ...documentHandlers,
  ...conversationHandlers,
  ...reminderHandlers,
  ...scheduleHandlers,
  // 通知面板随提醒页挂载，两个请求都得出得了服务端结果，否则会退化成「无法连接服务」。
  ...notificationHandlers,
  // 管理面六个操作：api、store 与后续管理视图共用同一份按契约校验入参形态的桩。
  ...adminHandlers,
  // S10-7 用户管理面三个操作（改密走 authHandlers 里的 `/auth/password`）：同一套桩纪律。
  ...usersHandlers,
);
