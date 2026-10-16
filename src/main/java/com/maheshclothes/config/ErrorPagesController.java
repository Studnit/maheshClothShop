package com.maheshclothes.config;

import com.maheshclothes.common.WebController;
import org.springframework.web.bind.annotation.GetMapping;

@WebController
public class ErrorPagesController {

    @GetMapping("/error/403")
    public String forbidden() {
        return "error/403";
    }
}
