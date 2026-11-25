package com.ticketsystem.api.notification.web;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.notification.NotificationService;
import com.ticketsystem.api.notification.NotificationService.NotificationView;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The notification centre: bell unread count, list, mark read. Agents and admins, their own
 * notifications only, about organisations they can still see.
 */
@RestController
@RequestMapping("/api/v1/agent/notifications")
public class AgentNotificationController {

  private final NotificationService notifications;

  public AgentNotificationController(NotificationService notifications) {
    this.notifications = notifications;
  }

  @GetMapping
  List<NotificationView> list(
      AccessScope scope,
      @RequestParam(required = false) Long before,
      @RequestParam(defaultValue = "30") int limit) {
    return notifications.list(scope, before, limit);
  }

  @GetMapping("/unread-count")
  Map<String, Long> unreadCount(AccessScope scope) {
    return Map.of("unread", notifications.unreadCount(scope));
  }

  @PostMapping("/{id}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void markRead(AccessScope scope, @PathVariable long id) {
    notifications.markRead(scope, id);
  }

  @PostMapping("/read-all")
  Map<String, Integer> markAllRead(AccessScope scope) {
    return Map.of("marked", notifications.markAllRead(scope));
  }
}
