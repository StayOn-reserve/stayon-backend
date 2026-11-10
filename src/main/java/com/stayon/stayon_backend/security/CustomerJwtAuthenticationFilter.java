package com.stayon.stayon_backend.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import com.stayon.stayon_backend.service.CustomerSessionService;
import org.springframework.web.server.ResponseStatusException;

/** Installed only inside the customer SecurityFilterChain, never as a servlet filter. */
@RequiredArgsConstructor
public class CustomerJwtAuthenticationFilter extends OncePerRequestFilter {
    private final CustomerJwtTokenProvider tokens;
    private final CustomerSessionService sessions;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "POST".equals(request.getMethod()) && ("/customer/auth/otp/challenges".equals(path)
                || "/customer/auth/otp/verifications".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            SecurityContextHolder.clearContext();
            response.sendError(401);
            return;
        }
        CustomerPrincipal principal;
        try {
            principal = tokens.parseAccessToken(authorization.substring(7));
            sessions.requireActive(principal, Set.of());
        } catch (ResponseStatusException e) {
            SecurityContextHolder.clearContext();
            response.sendError(e.getStatusCode().value());
            return;
        } catch (JwtException | IllegalArgumentException e) {
            SecurityContextHolder.clearContext();
            response.sendError(401);
            return;
        }
        var authorities = new java.util.ArrayList<>(List.of(new SimpleGrantedAuthority("CUSTOMER")));
        principal.scopes().forEach(scope -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope)));
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }
}
