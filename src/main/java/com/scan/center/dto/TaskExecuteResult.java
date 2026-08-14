package com.scan.center.dto;

import lombok.Data;

/** 任务启动结果：用于告知前端是否发生了 Git 增量同步 */
@Data
public class TaskExecuteResult {
  /** 是否发现远端 tip 更新并已同步工作区、重建清单 */
  private boolean gitUpdated;
  private String message;

  public static TaskExecuteResult of(boolean gitUpdated, String message) {
    TaskExecuteResult r = new TaskExecuteResult();
    r.setGitUpdated(gitUpdated);
    r.setMessage(message);
    return r;
  }
}
