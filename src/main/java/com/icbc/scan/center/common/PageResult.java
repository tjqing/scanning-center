package com.icbc.scan.center.common;

import java.util.List;

public class PageResult<T> {
  private List<T> list;
  private int pageNum;
  private int pageSize;
  private long total;

  public PageResult(List<T> list, int pageNum, int pageSize, long total) {
    this.list = list;
    this.pageNum = pageNum;
    this.pageSize = pageSize;
    this.total = total;
  }

  public List<T> getList() {
    return list;
  }

  public int getPageNum() {
    return pageNum;
  }

  public int getPageSize() {
    return pageSize;
  }

  public long getTotal() {
    return total;
  }
}
