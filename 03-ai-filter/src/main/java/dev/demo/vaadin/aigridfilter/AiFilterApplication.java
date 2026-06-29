package dev.demo.vaadin.aigridfilter;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.theme.aura.Aura;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

@StyleSheet(Aura.STYLESHEET)
@SpringBootApplication
@Push
public class AiFilterApplication implements AppShellConfigurator {

	public static void main(String[] args) {
		SpringApplication.run(AiFilterApplication.class, args);
	}

	/**
	 * On startup, set "Berlin Data Works"'s last order date to yesterday — so relative-date demos
	 * ("yesterday", "last week") always have a fresh hit (the H2 data is re-seeded on each start).
	 */
	@Bean
	ApplicationRunner setBerlinDataWorksLastOrderToYesterday(JdbcTemplate jdbcTemplate) {
		return args -> jdbcTemplate.update(
				"UPDATE customer SET last_order_date = ? WHERE company_name = ?",
				LocalDate.now().minusDays(1), "Berlin Data Works");
	}

}