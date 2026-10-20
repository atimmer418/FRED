// src/main/java/com/investingapp/backend/config/SecurityConfig.java
package com.investingapp.backend.config;

import com.investingapp.backend.security.jwt.AuthEntryPointJwt;
import com.investingapp.backend.security.jwt.AuthTokenFilter;
import com.investingapp.backend.security.services.UserDetailsServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value; // Import Value
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration; // Import CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource; // Import CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource; // Import UrlBasedCorsConfigurationSource

import java.util.Arrays; // Import Arrays
import java.util.List; // Import List

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Autowired
    private AuthEntryPointJwt unauthorizedHandler;

    // Additional allowed origin for testing on physical devices (set via environment variable)
    @Value("${cors.additional.origin:}")
    private String additionalCorsOrigin;


    @Bean
    public AuthTokenFilter authenticationJwtTokenFilter() {
        return new AuthTokenFilter();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Define your allowed origins.
        // This should include:
        // - localhost for local browser testing
        // - capacitor://localhost, ionic://localhost if you use those schemes for native builds
        // - The IP address origin for testing on your physical iPhone
        List<String> origins = new java.util.ArrayList<>(Arrays.asList(
                "http://localhost:8100",
                "http://localhost:4200",
                "http://localhost:3000",
                "capacitor://localhost",
                "ionic://localhost",
                "http://localhost",
                "https://potential-engine-97999gqqw9q4hpj7w-8100.app.github.dev",
                "https://local.fredvested.com"
        ));
        // Add optional additional origin from environment (e.g., for physical device testing)
        if (additionalCorsOrigin != null && !additionalCorsOrigin.isBlank()) {
            origins.add(additionalCorsOrigin);
        }
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Device-ID", "X-Device-Name", "Accept", "Origin", "X-Dev-Tools-Key"));
        configuration.setAllowCredentials(true);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration); // Apply this configuration to all paths
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource())) // ADD THIS LINE for CORS
            .csrf(AbstractHttpConfigurer::disable)
            .exceptionHandling(customizer -> customizer.authenticationEntryPoint(unauthorizedHandler))
            .securityContext(context -> context
                .securityContextRepository(new RequestAttributeSecurityContextRepository())
            )
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/.well-known/apple-app-site-association").permitAll()
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/plaid/webhook").permitAll() // signature-verified by PlaidWebhookVerificationFilter
                .requestMatchers("/api/plaid/create_link_token_anonymous").permitAll()
                .requestMatchers("/api/plaid/exchange_public_token_anonymous").permitAll()
                .requestMatchers("/api/passkey/**").permitAll()
                .requestMatchers("/api/user/should-prompt-reauth").permitAll() // Allow checking reauth status without authentication
                .requestMatchers("/api/dev/**").permitAll() // 🧪 DEV ONLY: registered only in local/dev/test/ci, and DevToolsKeyFilter requires X-Dev-Tools-Key
                .requestMatchers("/hello").permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable);

        http.authenticationProvider(authenticationProvider());
        http.addFilterBefore(authenticationJwtTokenFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}