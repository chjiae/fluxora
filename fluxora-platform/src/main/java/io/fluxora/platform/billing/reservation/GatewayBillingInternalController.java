package io.fluxora.platform.billing.reservation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Gateway 专用内部 API：受 HMAC 过滤器保护，永不接受用户 JWT 或用户 API Key 作为服务身份。 */
@RestController
@RequestMapping("/internal/gateway/billing/reservations")
public class GatewayBillingInternalController {
    private final BillingReservationService service;

    public GatewayBillingInternalController(BillingReservationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ReservationOutcome> reserve(@RequestBody GatewayReservationRequest request) {
        return ResponseEntity.ok(service.reserve(request));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<ReservationOutcome> status(@PathVariable String requestId) {
        return ResponseEntity.ok(service.status(requestId));
    }
}
