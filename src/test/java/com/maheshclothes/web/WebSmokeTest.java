package com.maheshclothes.web;

import com.maheshclothes.security.AppUserDetails;
import com.maheshclothes.user.AppUser;
import com.maheshclothes.user.PermissionRepository;
import com.maheshclothes.user.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Starts the whole application on an EMPTY local PostgreSQL database named by TEST_DB_URL (Flyway migrations + demo data), then opens every screen as an
 * owner in English and Marathi. It catches what unit tests cannot: JPA mappings that do not match the schema,
 * Thymeleaf expression errors and message keys that are missing from a language.
 * Skipped unless TEST_DB_URL is set, e.g.
 * TEST_DB_URL=jdbc:postgresql://localhost:5432/mahesh_test TEST_DB_USERNAME=postgres TEST_DB_PASSWORD=... mvn test
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"dev", "demo"})
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class WebSmokeTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("TEST_DB_URL"));
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USERNAME", "postgres"));
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");      // entities must match the Flyway schema exactly
        r.add("spring.thymeleaf.cache", () -> "false");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PermissionRepository permissions;

    private AppUserDetails owner() {
        Role role = new Role();
        role.setName("Owner");
        role.setPermissions(new HashSet<>(permissions.findAll()));
        AppUser u = new AppUser();
        u.setUsername("smoke");
        u.setFullName("Smoke Test");
        u.setPasswordHash("x");
        u.setRole(role);
        return new AppUserDetails(u);
    }

    private String firstId(String sql) {
        List<String> ids = jdbc.queryForList(sql, String.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private List<String> pages() {
        List<String> p = new ArrayList<>(List.of("/", "/pos", "/sales", "/returns", "/returns/new", "/customers", "/customers/new",
                "/products", "/products/new", "/catalog", "/inventory", "/inventory/insights", "/inventory/movements",
                "/inventory/adjust", "/inventory/count", "/inventory/labels", "/suppliers", "/suppliers/new", "/purchases",
                "/purchases/new", "/expenses", "/expenses/new", "/reports", "/reports/profit-loss", "/whatsapp", "/settings",
                "/admin/users", "/admin/users/new", "/admin/roles", "/admin/audit", "/account"));
        for (String key : List.of("daily_sales", "sales_by_product", "sales_by_category", "sales_by_cashier", "payments",
                "gst_summary", "returns", "profit_by_product", "customer_dues", "supplier_dues", "expenses", "purchases",
                "stock_valuation", "low_stock", "stock_movements")) {
            p.add("/reports/" + key);
        }
        String product = firstId("select id::text from products order by created_at limit 1");
        String sale = firstId("select id::text from sales_invoices order by sale_time limit 1");
        String customer = firstId("select id::text from customers order by created_at limit 1");
        String supplier = firstId("select id::text from suppliers order by created_at limit 1");
        String purchase = firstId("select id::text from purchase_invoices order by created_at limit 1");
        String ret = firstId("select id::text from sales_returns order by created_at limit 1");
        if (product != null) {
            p.add("/products/" + product);
            p.add("/products/" + product + "/edit");
        }
        if (sale != null) {
            p.add("/sales/" + sale);
            p.add("/sales/" + sale + "/print");
            p.add("/sales/" + sale + "/receipt");
            p.add("/returns/new?invoice=" + sale);
        }
        if (customer != null) {
            p.add("/customers/" + customer);
        }
        if (supplier != null) {
            p.add("/suppliers/" + supplier);
        }
        if (purchase != null) {
            p.add("/purchases/" + purchase);
        }
        if (ret != null) {
            p.add("/returns/" + ret);
        }
        return p;
    }

    @Test
    void everyScreenRendersInBothLanguages() throws Exception {
        for (String lang : new String[]{"en", "mr"}) {
            for (String page : pages()) {
                String url = page + (page.contains("?") ? "&" : "?") + "lang=" + lang;
                MvcResult res = mvc.perform(get(url).with(user(owner()))).andReturn();
                assertEquals(200, res.getResponse().getStatus(), url + " returned " + res.getResponse().getStatus());
                String body = res.getResponse().getContentAsString();
                assertFalse(body.contains("??"), url + " shows a missing message key");
                assertTrue(body.contains("</html>"), url + " did not finish rendering");
            }
        }
    }

    @Test
    void invoicePdfIsARealPdf() throws Exception {
        String sale = firstId("select id::text from sales_invoices order by sale_time limit 1");
        if (sale == null) {
            return;
        }
        MvcResult res = mvc.perform(get("/sales/" + sale + "/pdf").with(user(owner()))).andExpect(status().isOk()).andReturn();
        byte[] pdf = res.getResponse().getContentAsByteArray();
        assertTrue(pdf.length > 1000 && pdf[0] == '%' && pdf[1] == 'P' && pdf[2] == 'D' && pdf[3] == 'F', "not a PDF");
    }

    @Test
    void anonymousUsersAreSentToLoginAndApiRejectsThem() throws Exception {
        mvc.perform(get("/sales")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/v1/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/login")).andExpect(status().isOk());
    }
}
