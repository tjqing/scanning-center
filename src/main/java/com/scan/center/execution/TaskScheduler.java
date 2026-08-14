package com.scan.center.execution;

import com.scan.center.service.TaskService;
import java.sql.Timestamp;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时任务调度器：周期轮询 {@code scan_task} 中到点的一次性定时任务并触发执行。
 *条件：{@code schedule_type='ONCE'}、{@code status='SCHEDULED'}、{@code schedule_time} 已到。
 */
@Component
public class TaskScheduler {
  /** 调度日志 */
  private static final Logger log = LoggerFactory.getLogger(TaskScheduler.class);

  /** 直接查询待触发任务 ID */
  private final JdbcTemplate jdbc;
  /** 触发真正的任务执行入口 */
  private final TaskService taskService;

  /**
   * @param jdbc        JDBC 模板
   * @param taskService 任务服务
   */
  public TaskScheduler(JdbcTemplate jdbc, TaskService taskService) {
    this.jdbc = jdbc;
    this.taskService = taskService;
  }

  /**
   * 每 15 秒扫描一次到期的 SCHEDULED 任务并调用 {@link TaskService#execute(Long)}。
   * 单任务失败只记日志，不影响同批其他任务。
   */
  @Scheduled(fixedDelay = 15000L)
  public void scanDueTasks() {
    Timestamp now = new Timestamp(System.currentTimeMillis());
    // 只触发状态仍为 SCHEDULED 且到点的任务，避免重复触发
    List<Long> ids = jdbc.query(
        "SELECT id FROM scan_task WHERE deleted=FALSE AND schedule_type='ONCE' AND status='SCHEDULED' AND schedule_time IS NOT NULL AND schedule_time<=? ORDER BY schedule_time,id",
        new Object[] {now}, (rs, row) -> rs.getLong(1));
    for (Long id : ids) {
      try {
        log.info("scheduled task due, triggering taskId={}", id);
        taskService.execute(id);
      } catch (Exception e) {
        log.error("scheduled task trigger failed taskId={}", id, e);
      }
    }
  }

  /**
   * 服务启动后立即补跑一遍到期定时任务，避免停机窗口内漏触发。
   */
  @EventListener(ApplicationReadyEvent.class)
  public void recoverOnStartup() {
    scanDueTasks();
  }
}
