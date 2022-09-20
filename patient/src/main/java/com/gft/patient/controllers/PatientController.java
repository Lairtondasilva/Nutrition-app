package com.gft.patient.controllers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;

import com.gft.patient.data.PatientDetailsData;
import com.gft.patient.exception.JwtTokenMalformedException;
import com.gft.patient.exception.JwtTokenMissingException;
import com.gft.patient.exception.TokenRefreshException;
import com.gft.patient.models.PatientModel;
import com.gft.patient.models.RefreshToken;
import com.gft.patient.models.Roles;
import com.gft.patient.models.UserLogin;
import com.gft.patient.payload.JWTResponse;
import com.gft.patient.payload.TokenRefreshRequest;
import com.gft.patient.payload.TokenRefreshResponse;
import com.gft.patient.repositories.PatientRepository;
import com.gft.patient.service.PatientService;
import com.gft.patient.service.RefreshTokenService;
import com.gft.patient.util.JwtUtil;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

@RestController
@RequestMapping("/patient")
@CircuitBreaker(name = "default")
public class PatientController {

    @Autowired
    private PatientService patientService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private PatientRepository patientRepository;

    private Logger logger = LoggerFactory.getLogger(PatientController.class);

    @PostMapping("/login")
    public ResponseEntity<JWTResponse> login(@RequestBody UserLogin userLogin) {
        Authentication authentication = authenticationManager
                .authenticate(
                        new UsernamePasswordAuthenticationToken(userLogin.getEmail(), userLogin.getPassword(),
                                null));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        String jwt = jwtUtil.generateJwtToken(authentication);
        PatientDetailsData userDetails = (PatientDetailsData) authentication.getPrincipal();
        List<String> roles = userDetails.getAuthorities().stream()
                .map(item -> item.getAuthority())
                .collect(Collectors.toList());
        RefreshToken tokenRefresh = refreshTokenService.createRefreshToken(userDetails.getId());
        var response = new JWTResponse(jwt,
                tokenRefresh.getToken(),
                userDetails.getEmail(),
                roles);
        return ResponseEntity.ok(response);

    }

    @PostMapping("/refreshtoken")
    public ResponseEntity<?> refreshtoken(@Valid @RequestBody TokenRefreshRequest request) {
        String requestRefreshToken = request.getRefreshToken();

        return refreshTokenService.findByToken(requestRefreshToken)
                .map(refreshTokenService::verifyExpiration)
                .map(RefreshToken::getPatient)
                .map(patient -> {
                    String token = jwtUtil.generateTokenFromUsername(patient.getEmail());
                    return ResponseEntity.ok(new TokenRefreshResponse(token, requestRefreshToken));
                })
                .orElseThrow(() -> new TokenRefreshException(requestRefreshToken,
                        "Refresh token is not in database!"));
    }

    @Retry(name = "default")
    @GetMapping("/all")
    public ResponseEntity<List<PatientModel>> getAll() {
        return patientService.getAllPatients();
    }

    @Retry(name = "default")
    @GetMapping("/{id}")
    public ResponseEntity<PatientModel> getById(@PathVariable UUID id) {
        return patientService.getPatientById(id);
    }

    @GetMapping("/diet-groups/{dietsGroupsId}")
    @Retry(name = "default", fallbackMethod = "getAllByGroupIdFail")
    public ResponseEntity<List<PatientModel>> getAllByGroupId(@PathVariable UUID dietsGroupsId) {
        // var resp = new RestTemplate().getForEntity("http://localhost:8080/food",
        // String.class);
        return patientService.getAllPatientsByGroupId(dietsGroupsId);
    }

    public ResponseEntity<String> getAllByGroupIdFail(Exception e) {
        return ResponseEntity.badRequest().body("The service is currently unavailable.");
    }

    @PostMapping("/register")
    public ResponseEntity<PatientModel> registerPatient(@RequestBody @Valid PatientModel patient) {
        return patientService.registerPatient(patient);
    }

    @PutMapping
    public ResponseEntity<PatientModel> updatePatient(@RequestBody @Valid PatientModel patient) {
        return patientService.updatePatient(patient);
    }

    @DeleteMapping("/{id}")
    public void deletePatient(@PathVariable UUID id) {
        patientService.delete(id);
    }
}
