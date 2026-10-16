package com.maheshclothes.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maheshclothes.common.WebController;
import lombok.RequiredArgsConstructor;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@WebController
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboard;
    private final ObjectMapper mapper;

    @GetMapping("/")
    public String home(@RequestParam(defaultValue = "14") int days, Model model) throws JsonProcessingException {
        DashboardService.Data d = dashboard.load(days);
        model.addAttribute("d", d);
        model.addAttribute("days", d.trendDays());

        // chart data travels as one JSON attribute (CSP forbids inline scripts)
        Map<String, Object> charts = new LinkedHashMap<>();
        charts.put("days", d.trend().stream().map(p -> p.day().toString()).toList());
        charts.put("sales", d.trend().stream().map(p -> p.sales()).toList());
        charts.put("profit", d.trend().stream().map(p -> p.profit()).toList());
        charts.put("bills", d.trend().stream().map(p -> p.bills()).toList());
        charts.put("catNames", d.categories().stream().map(DashboardService.NameValue::name).toList());
        charts.put("catValues", d.categories().stream().map(DashboardService.NameValue::value).toList());
        charts.put("payNames", d.payments().stream().map(DashboardService.NameValue::name).toList());
        charts.put("payValues", d.payments().stream().map(DashboardService.NameValue::value).toList());
        charts.put("hourNames", d.hours().stream().map(h -> h.hour() + ":00").toList());
        charts.put("hourValues", d.hours().stream().map(DashboardService.HourPoint::bills).toList());
        model.addAttribute("chartJson", mapper.writeValueAsString(charts));
        return "dashboard";
    }
}
