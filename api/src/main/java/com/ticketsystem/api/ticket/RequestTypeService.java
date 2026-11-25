package com.ticketsystem.api.ticket;

import com.ticketsystem.api.access.AccessScope;
import com.ticketsystem.api.sla.Priority;
import com.ticketsystem.api.ticket.clientdata.RequestTypeRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Request types for the New ticket forms (portal without "Alert"). */
@Service
public class RequestTypeService {

  /** For supporters: with the starting priority. */
  public record RequestTypeOption(long id, String name, Priority defaultPriority) {}

  /**
   * For customers: the name and its short helper text, no priority (F6, priority is set by agents).
   * {@code description} may be null for types an admin added without one.
   */
  public record PortalRequestType(long id, String name, String description) {}

  private final RequestTypeRepository requestTypes;

  public RequestTypeService(RequestTypeRepository requestTypes) {
    this.requestTypes = requestTypes;
  }

  @Transactional(readOnly = true)
  public List<RequestTypeOption> forAgents(AccessScope scope) {
    scope.requireSupporterOrSystem();
    return requestTypes.list(scope).stream()
        .map(r -> new RequestTypeOption(r.id(), r.name(), r.defaultPriority()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<PortalRequestType> forPortal(AccessScope scope) {
    return requestTypes.list(scope).stream()
        .filter(r -> r.portalVisible())
        .map(r -> new PortalRequestType(r.id(), r.name(), r.description()))
        .toList();
  }
}
