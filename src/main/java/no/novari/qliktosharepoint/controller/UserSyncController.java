package no.novari.qliktosharepoint.controller;

import no.novari.qliktosharepoint.service.UserSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sync")
public class UserSyncController {
    private final UserSyncService userSyncService;

    public UserSyncController(UserSyncService userSyncService) {
        this.userSyncService = userSyncService;
    }

    @PostMapping("/qlik-users")
    public ResponseEntity<String> syncQlikUsers() {
        userSyncService.runSyncOnce();
        return ResponseEntity.ok("Sync started/finished");
    }
}
