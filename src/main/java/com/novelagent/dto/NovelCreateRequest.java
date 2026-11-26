package com.novelagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class NovelCreateRequest {

    @NotBlank(message = "书名不能为空")
    @Size(max = 500)
    private String title;

    @Size(max = 255)
    private String author;

    private String description;
}
