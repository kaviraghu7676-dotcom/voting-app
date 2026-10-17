
package com.voting.controller;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;

@WebServlet("/vote")
public class VotingServlet extends HttpServlet {

    private static final String COOKIE_NAME = "demo_voter";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Connection getConnection() throws Exception {
        String url = System.getenv("DB_URL");
        String user = System.getenv("DB_USER");
        String password = System.getenv("DB_PASSWORD");

        if (url == null || user == null || password == null) {
            throw new IllegalStateException(
                "Database environment variables are missing");
        }

        return DriverManager.getConnection(url, user, password);
    }

    private String getVoterToken(HttpServletRequest request,
                                 HttpServletResponse response) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (COOKIE_NAME.equals(cookie.getName())
                        && cookie.getValue().matches("[a-f0-9]{64}")) {
                    return cookie.getValue();
                }
            }
        }

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);

        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setHttpOnly(true);
        cookie.setPath(request.getContextPath().isEmpty()
                ? "/" : request.getContextPath());
        cookie.setMaxAge(60 * 60 * 24 * 30);

        // Enable Secure when the app is served over HTTPS.
        cookie.setSecure(request.isSecure());
        response.addCookie(cookie);

        return token;
    }

    private String hashToken(String token) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");

        getVoterToken(request, response);

        try (PrintWriter out = response.getWriter();
             Connection con = getConnection()) {

            out.println("<!DOCTYPE html><html><head>");
            out.println("<meta name='viewport' content='width=device-width'>");
            out.println("<title>Voting Application</title></head><body>");
            out.println("<h1>Online Voting Demo</h1>");
            out.println("<h2>Choose a candidate</h2>");
            out.println("<form method='post' action='vote'>");

            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT id, name FROM candidates ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    int id = rs.getInt("id");
                    String name = rs.getString("name");

                    out.printf(
                        "<p><label><input type='radio' name='candidateId' " +
                        "value='%d' required> %s</label></p>%n",
                        id, escapeHtml(name));
                }
            }

            out.println("<button type='submit'>Submit Vote</button>");
            out.println("</form><hr><h2>Current Results</h2>");

            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT c.name, COUNT(v.id) AS total " +
                    "FROM candidates c LEFT JOIN votes v " +
                    "ON c.id = v.candidate_id " +
                    "GROUP BY c.id, c.name ORDER BY c.id");
                 ResultSet rs = ps.executeQuery()) {

                out.println("<ul>");
                while (rs.next()) {
                    out.printf("<li>%s: %d votes</li>%n",
                            escapeHtml(rs.getString("name")),
                            rs.getLong("total"));
                }
                out.println("</ul>");
            }

            out.println("</body></html>");

        } catch (Exception e) {
            getServletContext().log("Unable to display voting page", e);
            response.sendError(500, "Unable to load voting page.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        String selected = request.getParameter("candidateId");

        if (selected == null || !selected.matches("[0-9]{1,9}")) {
            response.sendError(400, "Invalid candidate selection.");
            return;
        }

        try {
            int candidateId = Integer.parseInt(selected);
            String token = getVoterToken(request, response);
            String tokenHash = hashToken(token);

            try (Connection con = getConnection()) {
                con.setAutoCommit(false);

                try {
                    try (PreparedStatement check = con.prepareStatement(
                            "SELECT id FROM candidates WHERE id = ?")) {
                        check.setInt(1, candidateId);

                        try (ResultSet rs = check.executeQuery()) {
                            if (!rs.next()) {
                                con.rollback();
                                response.sendError(400,
                                        "Candidate does not exist.");
                                return;
                            }
                        }
                    }

                    try (PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO votes " +
                            "(candidate_id, voter_token_hash) VALUES (?, ?)")) {
                        ps.setInt(1, candidateId);
                        ps.setString(2, tokenHash);
                        ps.executeUpdate();
                    }

                    con.commit();

                } catch (java.sql.SQLException e) {
                    con.rollback();

                    if ("23000".equals(e.getSQLState())) {
                        response.sendError(409,
                                "This browser has already voted.");
                        return;
                    }

                    throw e;
                }
            }

            response.sendRedirect(request.getContextPath() + "/vote");

        } catch (Exception e) {
            getServletContext().log("Unable to record vote", e);
            if (!response.isCommitted()) {
                response.sendError(500,
                        "Unable to record vote. Check server configuration.");
            }
        }
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
    }
}
