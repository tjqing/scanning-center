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

@Service
public class ResultService {
  private final ResultMapper mapper;

  public ResultService(ResultMapper mapper) {
    this.mapper = mapper;
  }

  public PageResult<ScanResult> page(String keyword, int page, int size) {
    int p = Math.max(page, 1), s = Math.min(Math.max(size, 1), 100);
    return new PageResult<ScanResult>(
        mapper.page(keyword, (p - 1) * s, s), p, s, mapper.count(keyword));
  }

  public ScanResult get(Long id) {
    ScanResult v = mapper.findById(id);
    if (v == null) throw new BusinessException(40001, "扫描结果不存在");
    return v;
  }

  public PageResult<ScanIssue> issues(
      String keyword, String status, String risk, Long resultId, int page, int size) {
    int p = Math.max(page, 1), s = Math.min(Math.max(size, 1), 100);
    return new PageResult<ScanIssue>(
        mapper.issuePage(keyword, status, risk, resultId, (p - 1) * s, s),
        p,
        s,
        mapper.issueCount(keyword, status, risk, resultId));
  }

  public ScanIssue issue(Long id) {
    ScanIssue v = mapper.findIssue(id);
    if (v == null) throw new BusinessException(40002, "问题不存在");
    return v;
  }

  public byte[] exportIssues(String keyword, String status, String risk, Long resultId) {
    List<ScanIssue> rows = mapper.issueExport(keyword, status, risk, resultId);
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("扫描结果明细");
      String[] headers = {"任务编号", "任务名称", "问题标题", "风险等级", "扫描源", "文件位置", "开始行", "结束行", "扫描规则", "命中内容", "问题说明", "整改建议", "处理状态", "处理说明", "创建时间"};
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
        Object[] values = {issue.getTaskNo(), issue.getTaskName(), issue.getTitle(), issue.getRiskLevel(), issue.getRepositoryName(), issue.getFilePath(), issue.getStartLine(), issue.getEndLine(), issue.getRuleName(), issue.getMatchedContent(), issue.getIssueDescription(), issue.getSuggestion(), issue.getStatus(), issue.getHandleComment(), issue.getCreateTime()};
        for (int c = 0; c < values.length; c++) {
          Cell cell = row.createCell(c); Object value = values[c];
          if (value instanceof Number) cell.setCellValue(((Number) value).doubleValue());
          else if (value instanceof Date) { cell.setCellValue((Date) value); cell.setCellStyle(dateStyle); }
          else cell.setCellValue(value == null ? "" : String.valueOf(value));
          if (c == 2 || c == 5 || (c >= 9 && c <= 13)) cell.setCellStyle(wrapStyle);
        }
      }
      sheet.createFreezePane(0, 1);
      sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rowIndex - 1), 0, headers.length - 1));
      int[] widths = {22, 24, 30, 12, 22, 42, 10, 10, 24, 36, 36, 36, 14, 30, 22};
      for (int c = 0; c < widths.length; c++) sheet.setColumnWidth(c, widths[c] * 256);
      workbook.write(output);
      return output.toByteArray();
    } catch (Exception e) {
      throw new BusinessException(40004, "扫描结果明细导出失败");
    }
  }

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
