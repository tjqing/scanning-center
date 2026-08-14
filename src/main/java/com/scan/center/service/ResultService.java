package com.scan.center.service;

import com.scan.center.common.*;
import com.scan.center.dto.IssueStatusDTO;
import com.scan.center.exception.BusinessException;
import com.scan.center.mapper.ResultMapper;
import com.scan.center.model.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 扫描结果与问题明细的领域服务。
 *负责扫描结果分页查询、问题明细检索、Excel 导出及问题处理状态更新。
 */
@Service
public class ResultService {

  /** 扫描结果与问题明细数据访问层 */
  private final ResultMapper mapper;

  /**
   * @param mapper 扫描结果 Mapper
   */
  public ResultService(ResultMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * 分页查询扫描结果汇总。
   *
   * @param keyword     关键词，匹配任务编号/名称等
   * @param application 应用筛选，可为空
   * @param versionNo   版本号筛选，可为空
   * @param page        页码，小于 1 时按 1 处理
   * @param size        每页条数，限制在 1~100
   * @return 分页结果
   */
  public PageResult<ScanResult> page(String keyword, String application, String versionNo, int page, int size) {
    int p = Math.max(page, 1), s = Math.min(Math.max(size, 1), 100);
    return new PageResult<ScanResult>(
        mapper.page(keyword, application, versionNo, (p - 1) * s, s),
        p,
        s,
        mapper.count(keyword, application, versionNo));
  }

  /**
   * 按主键获取扫描结果。
   *
   * @param id 扫描结果 ID
   * @return 扫描结果实体
   * @throws BusinessException 结果不存在时抛出（40001）
   */
  public ScanResult get(Long id) {
    ScanResult v = mapper.findById(id);
    if (v == null) throw new BusinessException(40001, "扫描结果不存在");
    return v;
  }

  /**
   * 分页查询扫描问题明细。
   *
   * @param keyword     关键词
   * @param status      处理状态筛选
   * @param risk        风险等级筛选
   * @param resultId    所属扫描结果 ID，可为空
   * @param application 应用筛选
   * @param versionNo   版本号筛选
   * @param page        页码
   * @param size        每页条数，限制在 1~100
   * @return 问题明细分页结果
   */
  public PageResult<ScanIssue> issues(
      String keyword, String status, String risk, Long resultId, String application, String versionNo, int page, int size) {
    int p = Math.max(page, 1), s = Math.min(Math.max(size, 1), 100);
    return new PageResult<ScanIssue>(
        mapper.issuePage(keyword, status, risk, resultId, application, versionNo, (p - 1) * s, s),
        p,
        s,
        mapper.issueCount(keyword, status, risk, resultId, application, versionNo));
  }

  /**
   * 按主键获取单条问题明细。
   *
   * @param id 问题 ID
   * @return 问题实体
   * @throws BusinessException 问题不存在时抛出（40002）
   */
  public ScanIssue issue(Long id) {
    ScanIssue v = mapper.findIssue(id);
    if (v == null) throw new BusinessException(40002, "问题不存在");
    return v;
  }

  /**
   * 按筛选条件导出全部问题明细为 Excel（xlsx）。
   *
   * @param keyword     关键词
   * @param status      处理状态
   * @param risk        风险等级
   * @param resultId    扫描结果 ID
   * @param application 应用
   * @param versionNo   版本号
   * @return xlsx 文件字节数组
   * @throws BusinessException 导出过程失败时抛出（40004）
   */
  public byte[] exportIssues(String keyword, String status, String risk, Long resultId, String application, String versionNo) {
    List<ScanIssue> rows = mapper.issueExport(keyword, status, risk, resultId, application, versionNo);
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("扫描结果明细");
      String[] headers = {"任务编号", "任务名称", "应用", "版本", "问题标题", "风险等级", "扫描源", "文件位置", "开始行", "结束行", "扫描规则", "命中内容", "问题说明", "整改建议", "处理状态", "处理说明", "创建时间"};
      CellStyle headerStyle = workbook.createCellStyle();
      headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
      headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      Font headerFont = workbook.createFont();
      headerFont.setBold(true);
      headerFont.setColor(IndexedColors.WHITE.getIndex());
      headerStyle.setFont(headerFont);
      headerStyle.setAlignment(HorizontalAlignment.CENTER);
      CellStyle dateStyle = workbook.createCellStyle();
      dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
      CellStyle wrapStyle = workbook.createCellStyle();
      wrapStyle.setWrapText(true);
      Row header = sheet.createRow(0);
      for (int c = 0; c < headers.length; c++) { Cell cell = header.createCell(c); cell.setCellValue(headers[c]); cell.setCellStyle(headerStyle); }
      int rowIndex = 1;
      for (ScanIssue issue : rows) {
        Row row = sheet.createRow(rowIndex++);
        Object[] values = {issue.getTaskNo(), issue.getTaskName(), issue.getApplication(), issue.getVersionNo(), issue.getTitle(), issue.getRiskLevel(), issue.getRepositoryName(), issue.getFilePath(), issue.getStartLine(), issue.getEndLine(), issue.getRuleName(), issue.getMatchedContent(), issue.getIssueDescription(), issue.getSuggestion(), issue.getStatus(), issue.getHandleComment(), issue.getCreateTime()};
        for (int c = 0; c < values.length; c++) {
          Cell cell = row.createCell(c); Object value = values[c];
          if (value instanceof Number) cell.setCellValue(((Number) value).doubleValue());
          else if (value instanceof Date) { cell.setCellValue((Date) value); cell.setCellStyle(dateStyle); }
          else cell.setCellValue(value == null ? "" : String.valueOf(value));
          if (c == 4 || c == 7 || (c >= 11 && c <= 15)) cell.setCellStyle(wrapStyle);
        }
      }
      sheet.createFreezePane(0, 1);
      sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rowIndex - 1), 0, headers.length - 1));
      int[] widths = {22, 24, 12, 14, 30, 12, 22, 42, 10, 10, 24, 36, 36, 36, 14, 30, 22};
      for (int c = 0; c < widths.length; c++) sheet.setColumnWidth(c, widths[c] * 256);
      workbook.write(output);
      return output.toByteArray();
    } catch (Exception e) {
      throw new BusinessException(40004, "扫描结果明细导出失败");
    }
  }

  /**
   * 更新问题处理状态及说明。
   *
   * @param id  问题 ID
   * @param dto 目标状态与处理说明，status 允许 PENDING/CONFIRMED/RESOLVED/IGNORED
   * @throws BusinessException 问题不存在（40002）或状态不合法（40003）
   */
  @Transactional(rollbackFor = Exception.class)
  public void updateIssue(Long id, IssueStatusDTO dto) {
    ScanIssue old = issue(id);
    String s = dto.getStatus();
    if (!"CONFIRMED".equals(s)
        && !"RESOLVED".equals(s)
        && !"IGNORED".equals(s)
        && !"PENDING".equals(s)) throw new BusinessException(40003, "问题状态不合法");
    mapper.updateIssue(old.getId(), s, dto.getComment(), "system");
  }
}
