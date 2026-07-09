import { useAuthStore } from '@/store/modules/auth';
import { runUploadPool } from '@/utils/uploadScheduler';
import { SetupStoreId, UploadStatus } from '@/enum';
import { REQUEST_ID_KEY } from '~/packages/axios/src';
import { nanoid } from '~/packages/utils/src';

/** ZH-F02: 分片上传有界并发度 (主档 4, 匹配 README 主结论预注册; env 可覆盖) */
const UPLOAD_CONCURRENCY = Number(import.meta.env.VITE_UPLOAD_CONCURRENCY) || 4;

export const useKnowledgeBaseStore = defineStore(SetupStoreId.KnowledgeBase, () => {
  const authStore = useAuthStore();
  const tasks = ref<Api.KnowledgeBase.UploadTask[]>([]);
  const activeUploads = ref<Set<string>>(new Set());

  function getTaskIdentity(task: Pick<Api.KnowledgeBase.UploadTask, 'fileMd5' | 'userId'>) {
    const currentUserId = String(authStore.userInfo.id || '');
    return `${task.fileMd5}::${task.userId || currentUserId}`;
  }

  async function uploadChunk(task: Api.KnowledgeBase.UploadTask, chunkIndex: number): Promise<boolean> {
    const chunkStart = chunkIndex * chunkSize;
    const chunkEnd = Math.min(chunkStart + chunkSize, task.totalSize);
    const chunk = task.file.slice(chunkStart, chunkEnd);

    const requestId = nanoid();
    task.requestIds ??= [];
    task.requestIds.push(requestId);
    const { error, data } = await request<Api.KnowledgeBase.Progress>({
      url: '/upload/chunk',
      method: 'POST',
      data: {
        file: chunk,
        fileMd5: task.fileMd5,
        chunkIndex,
        totalSize: task.totalSize,
        fileName: task.fileName,
        orgTag: task.orgTag,
        isPublic: task.isPublic ?? false
      },
      headers: {
        'Content-Type': 'multipart/form-data',
        [REQUEST_ID_KEY]: requestId
      },
      timeout: 10 * 60 * 1000
    });

    task.requestIds = task.requestIds.filter(id => id !== requestId);

    if (error) return false;

    // 更新任务状态 (R10: 并集非替换 — 并发下响应非确定序到达, data.uploaded 为后端 bitmap 当时刻快照,
    //   可能未含其他在飞 worker 的 SETBIT; 并集保证 uploadedChunks 单调只增, 与 Redis SETBIT 单调性一致,
    //   避免 stale 快照覆盖导致 pool 收口后 length===totalChunks 失败 → merge 不触发 → 任务卡死)
    const updatedTask = tasks.value.find(t => getTaskIdentity(t) === getTaskIdentity(task))!;
    updatedTask.uploadedChunks = Array.from(new Set([...updatedTask.uploadedChunks, ...data.uploaded])).sort(
      (a, b) => a - b
    );
    updatedTask.progress = Number.parseFloat(data.progress.toFixed(2));

    return true;
  }

  async function mergeFile(task: Api.KnowledgeBase.UploadTask) {
    try {
      const { error, data } = await request<Api.KnowledgeBase.MergeResult>({
        url: '/upload/merge',
        method: 'POST',
        data: { fileMd5: task.fileMd5, fileName: task.fileName }
      });
      if (error) return false;

      // 更新任务状态为已完成
      const index = tasks.value.findIndex(t => getTaskIdentity(t) === getTaskIdentity(task));
      tasks.value[index].status = UploadStatus.Completed;
      tasks.value[index].estimatedEmbeddingTokens = data?.estimatedEmbeddingTokens;
      tasks.value[index].estimatedChunkCount = data?.estimatedChunkCount;

      if (data?.estimatedEmbeddingTokens) {
        const tokenLabel = Number(data.estimatedEmbeddingTokens).toLocaleString();
        const chunkLabel = Number(data.estimatedChunkCount || 0).toLocaleString();
        window.$message?.success(`上传完成，预计向量化消耗 ${tokenLabel} Tokens（${chunkLabel} 个切片）`);
      }
      return true;
    } catch {
      return false;
    }
  }

  /**
   * 异步函数：将上传请求加入队列
   *
   * 本函数处理上传任务的排队和初始化工作它首先检查是否存在相同的文件， 如果不存在，则创建一个新的上传任务，并将其添加到任务队列中最后启动上传流程
   *
   * @param form 包含上传信息的表单，包括文件列表和是否公开的标签
   * @returns 返回一个上传任务对象，无论是已存在的还是新创建的
   */
  async function enqueueUpload(form: Api.KnowledgeBase.Form) {
    // 获取文件列表中的第一个文件
    const file = form.fileList![0].file!;
    // 计算文件的MD5值，用于唯一标识文件
    const md5 = await calculateMD5(file);

    // 检查是否已存在相同文件
    const currentUserId = String(authStore.userInfo.id || '');
    const taskIdentity = `${md5}::${currentUserId}`;
    const existingTask = tasks.value.find(t => getTaskIdentity(t) === taskIdentity);
    if (existingTask) {
      // 如果存在相同文件，直接返回该上传任务
      if (existingTask.status === UploadStatus.Completed) {
        window.$message?.error('文件已存在');
        return;
      } else if (existingTask.status === UploadStatus.Pending || existingTask.status === UploadStatus.Uploading) {
        window.$message?.error('文件正在上传中');
        return;
      } else if (existingTask.status === UploadStatus.Break) {
        existingTask.status = UploadStatus.Pending;
        startUpload();
        return;
      }
    }

    // 创建新的上传任务对象
    const newTask: Api.KnowledgeBase.UploadTask = {
      file,
      chunk: null,
      chunkIndex: 0,
      fileMd5: md5,
      fileName: file.name,
      totalSize: file.size,
      userId: currentUserId,
      public: form.isPublic,
      isPublic: form.isPublic,
      uploadedChunks: [],
      progress: 0,
      status: UploadStatus.Pending,
      orgTag: form.orgTag
    };

    newTask.orgTagName = form.orgTagName ?? null;

    // 将新的上传任务添加到任务队列中
    tasks.value.push(newTask);
    // 启动上传流程
    startUpload();
    // 返回新的上传任务
  }

  /** 启动文件上传的异步函数 该函数负责从待上传队列中启动文件上传任务，并管理并发上传的数量 */
  async function startUpload() {
    // 限制可同时上传的文件个数
    if (activeUploads.value.size >= 3) return;
    // 获取待上传的文件
    const pendingTasks = tasks.value.filter(
      t => t.status === UploadStatus.Pending && !activeUploads.value.has(getTaskIdentity(t))
    );

    // 如果没有待上传的文件，则直接返回
    if (pendingTasks.length === 0) return;

    // 获取第一个待上传的文件
    const task = pendingTasks[0];
    task.status = UploadStatus.Uploading;
    activeUploads.value.add(getTaskIdentity(task));

    // 计算文件总片数
    const totalChunks = Math.ceil(task.totalSize / chunkSize);

    try {
      if (task.uploadedChunks.length === totalChunks) {
        const success = await mergeFile(task);
        if (!success) throw new Error('文件合并失败');
        return;
      }
      // 剩余分片队列 (恢复友好: 跳过已传)
      const remaining: number[] = [];
      for (let i = 0; i < totalChunks; i += 1) {
        if (!task.uploadedChunks.includes(i)) remaining.push(i);
      }
      // 有界并发 worker pool (R1/R2/R3): N workers 从 remaining 拉取下一 index, fail-fast
      await runUploadPool(remaining, UPLOAD_CONCURRENCY, async i => {
        const success = await uploadChunk(task, i);
        if (!success) throw new Error(`分片 ${i} 上传失败`);
      });
      // merge 收口 (R2): pool 全成功后单次 merge, 无并发竞态
      const updated = tasks.value.find(t => getTaskIdentity(t) === getTaskIdentity(task))!;
      if (updated.uploadedChunks.length === totalChunks) {
        const success = await mergeFile(task);
        if (!success) throw new Error('文件合并失败');
      }
    } catch (e) {
      console.error('%c [ 👉 upload error 👈 ]-168', 'font-size:16px; background:#94cc97; color:#d8ffdb;', e);
      // 如果上传失败，则将任务状态设置为中断
      const index = tasks.value.findIndex(t => getTaskIdentity(t) === getTaskIdentity(task));
      tasks.value[index].status = UploadStatus.Break;
    } finally {
      // 无论成功或失败，都从活跃队列中移除
      activeUploads.value.delete(getTaskIdentity(task));
      // 继续下一个任务
      startUpload();
    }
  }

  return {
    tasks,
    activeUploads,
    enqueueUpload,
    startUpload
  };
});
