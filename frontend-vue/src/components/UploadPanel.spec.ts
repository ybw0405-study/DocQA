import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import UploadPanel from './UploadPanel.vue';
import { bindSessionToAuthStore } from '../stores/sessionBridge';
import { useAuthStore } from '../stores/auth';
import { useUploadsStore } from '../stores/uploads';
import type { UploadTask } from '../stores/uploads';
import { DEMO_TOKEN } from '../../tests/fixtures';

function task(overrides: Partial<UploadTask>): UploadTask {
  return {
    id: 'task-1',
    fileName: 'note.pdf',
    sizeBytes: 2048,
    phase: 'uploading',
    progressBytes: 1024,
    partsSent: 1,
    partCount: 2,
    documentId: null,
    status: null,
    errorCode: null,
    detail: null,
    ...overrides,
  };
}

async function mountPanel() {
  const wrapper = mount(UploadPanel);
  const input = wrapper.find('input[type="file"]');
  return { wrapper, input };
}

describe('UploadPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindSessionToAuthStore();
    useAuthStore().token = DEMO_TOKEN;
  });

  it('starts one task per picked file and releases the input for the same pick again', async () => {
    const upload = vi.spyOn(useUploadsStore(), 'upload').mockResolvedValue(undefined);
    const { wrapper, input } = await mountPanel();
    const files = [new File(['a'], 'a.pdf'), new File(['b'], 'b.txt')];

    Object.defineProperty(input.element, 'files', { value: files, configurable: true });
    await input.trigger('change');

    expect(upload.mock.calls.map(([file]) => file.name)).toEqual(['a.pdf', 'b.txt']);
    expect((input.element as HTMLInputElement).value).toBe('');
    expect(input.attributes('accept')).toBe('.pdf,.pptx,.docx,.txt');
    expect(wrapper.text()).toContain('还没有上传任务。');
  });

  it('offers a cancel action and a byte progress bar only while the task is running', async () => {
    const uploads = useUploadsStore();
    uploads.tasks.push(task({ id: 'running', phase: 'indexing' }), task({ id: 'ended', phase: 'done', status: 'INDEXED' }));
    const cancel = vi.spyOn(uploads, 'cancel');
    const { wrapper } = await mountPanel();

    const rows = wrapper.findAll('.upload-task');
    expect(rows).toHaveLength(2);
    expect(rows[0]?.find('progress').attributes('max')).toBe('2048');
    expect(rows[0]?.text()).toContain('等待解析与索引');
    expect(rows[1]?.find('progress').exists()).toBe(false);
    expect(rows[1]?.text()).toContain('服务端状态：已可检索');

    await rows[0]?.find('button').trigger('click');

    expect(cancel).toHaveBeenCalledWith('running');
  });

  it('renders an untrusted file name and detail as escaped text', async () => {
    const uploads = useUploadsStore();
    uploads.tasks.push(task({
      id: 'failed',
      fileName: '<img src=x onerror=alert(1)>.pdf',
      phase: 'failed',
      errorCode: 'CLIENT-DOC-SIZE-001',
      detail: '<script>steal()</script> 超出 50MiB 上限。',
    }));
    const { wrapper } = await mountPanel();
    const row = wrapper.find('.upload-task');

    expect(row.text()).toContain('<img src=x onerror=alert(1)>.pdf');
    expect(row.text()).toContain('<script>steal()</script> 超出 50MiB 上限。');
    expect(row.text()).toContain('错误码 CLIENT-DOC-SIZE-001');
    expect(wrapper.find('img').exists()).toBe(false);
    expect(wrapper.find('script').exists()).toBe(false);
  });
});
