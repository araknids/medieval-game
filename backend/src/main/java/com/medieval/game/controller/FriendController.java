package com.medieval.game.controller;

import com.medieval.game.model.Player;
import com.medieval.game.service.FriendService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** [LEADERBOARDS] Amizade entre jogadores. */
@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendController {

    private final FriendService friendService;
    private final PlayerService playerService;

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        FriendService.FriendList l = friendService.list(me(auth));
        return ResponseEntity.ok(Map.of(
                "friends",  mapRows(l.friends()),
                "incoming", mapRows(l.incoming()),
                "outgoing", mapRows(l.outgoing())));
    }

    @PostMapping("/request/{playerId}")
    public ResponseEntity<?> request(@PathVariable Long playerId, Authentication auth) {
        friendService.request(me(auth), playerId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/accept/{id}")
    public ResponseEntity<?> accept(@PathVariable Long id, Authentication auth) {
        friendService.accept(me(auth), id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/decline/{id}")
    public ResponseEntity<?> decline(@PathVariable Long id, Authentication auth) {
        friendService.decline(me(auth), id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/{playerId}")
    public ResponseEntity<?> remove(@PathVariable Long playerId, Authentication auth) {
        friendService.remove(me(auth), playerId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private Player me(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    private List<Map<String, Object>> mapRows(List<FriendService.FriendRow> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (FriendService.FriendRow r : rows) {
            out.add(Map.of(
                    "playerId",    r.playerId(),
                    "warriorName", r.name(),
                    "title",       r.title(),
                    "level",       r.level(),
                    "classId",     r.classId(),
                    "requestId",   r.requestId()));
        }
        return out;
    }
}
