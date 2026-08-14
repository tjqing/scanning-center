package com.scan.center.service;

import com.scan.center.dto.DetailDesignAiContext;
import java.util.Arrays;
import java.util.Collections;
import org.springframework.stereotype.Service;

/**
 * 详细设计文档获取与 AI 上下文装配（桩实现，业务代码后续补充）。
 */
@Service
public class DetailDesignDocumentService {

  /**
   * 按应用 ID + 版本号拉取详细设计正文，并装配为可直接拼进 AI 上下文的对象。
   *
   * 后续实现步骤（当前仅注释占位，方法末尾返回假装已处理好的结果）：
   * 1. 查第三方 e企研接口：根据用户传入的 application（应用 ID）与 versionNo（版本号），
   *    调用第三方接口查询该应用该版本下的多个需求子条目（如 subitem_no 列表）。
   * 2. 查本地多数据源库 doc_info：切换到详细设计所在的额外数据源，按上一步得到的子条目编号，
   *    查询表 doc_info 中与这些子条目匹配的记录；对每个子条目取 version 最大且 valid = 1 的那一条（最新有效版本）。
   * 3. 有结果：将各条子条目对应记录的 content 字段按子条目顺序拼接成 Markdown 字符串，
   *    再包装进 AI 上下文文本（含应用、版本、子条目标识等前置说明）。任务启动后把该文本拼入 AI 请求上下文。
   *    默认文档形态为 .md。
   * 4. 无结果：提示「数据库没有」；前端根据 found=false 弹框询问用户是否跳转，
   *    开启 iframe 打开 e企研页面（如 ip:端口/subitemDesign）自行下载后上传，
   *    上传文件默认必须是 md 格式；任务开始时同样将上传得到的正文拼进 AI 上下文。
   *
   * @param application 应用 ID
   * @param versionNo   版本号（如 YYYYMM）
   * @return 已装配好的 AI 上下文对象（桩：假装查库成功并拼好）
   */
  public DetailDesignAiContext buildAiContext(String application, String versionNo) {
    // TODO: 1) 调 e企研：application + versionNo → List<subitem_no>
    // TODO: 2) 多数据源查 doc_info：subitem_no IN (...) AND valid=1，按 subitem 取 version 最大
    // TODO: 3) 拼接 content → markdownContent / aiContextText；无行则 found=false + downloadPageUrl

    // 假装已处理好：返回可直接装配到 AI 上下文的对象
    DetailDesignAiContext result = new DetailDesignAiContext();
    result.setFound(true);
    result.setApplication(application);
    result.setVersionNo(versionNo);
    result.setSubitemNos(Collections.unmodifiableList(Arrays.asList("SUBITEM-DEMO-001", "SUBITEM-DEMO-002")));
    result.setMessage("桩数据：已装配 2 条需求子条目的详细设计正文（后续替换为真实查询）");
    result.setMarkdownContent(
        "# 详细设计\n\n"
            + "## 子条目 SUBITEM-DEMO-001\n\n（桩 content）\n\n"
            + "## 子条目 SUBITEM-DEMO-002\n\n（桩 content）\n");
    result.setAiContextText(
        "【详细设计文档上下文】\n"
            + "应用：" + nullToEmpty(application) + "\n"
            + "版本：" + nullToEmpty(versionNo) + "\n"
            + "子条目：SUBITEM-DEMO-001, SUBITEM-DEMO-002\n\n"
            + result.getMarkdownContent());
    result.setDownloadPageUrl(null);
    return result;
  }

  /**
   * 空串安全转换。
   *
   * @param value 可为 null
   * @return 非 null 字符串
   */
  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
