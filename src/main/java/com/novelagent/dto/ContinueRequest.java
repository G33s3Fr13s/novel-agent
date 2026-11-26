package com.novelagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class ContinueRequest {

    @NotBlank(message = "请选择小说")
    private String novelId;

    /** 参考章节 ID 列表（多选），为空则使用全部章节 */
    private List<String> chapterIds;

    /** 前言：续写内容的前置背景，如 "上一章讲到林夜突破境界..." */
    private String foreword;

    /** 后语：续写方向指引，如 "下一章写林夜挑战长老" */
    private String afterword;

    /** 自定义提示词，补充对 AI 的额外指令 */
    private String prompt;

    /** 续写章数 (1-5)，默认 1 */
    private int chapterCount = 1;

    /** 联网搜索勾选的站点 key 列表：qidian / fanqie / qimao，null/空 = 不搜索 */
    private List<String> searchSites;

    /** 联网搜索提示词，留空则用 foreword+afterword+prompt 拼接 */
    private String searchHint;
}
