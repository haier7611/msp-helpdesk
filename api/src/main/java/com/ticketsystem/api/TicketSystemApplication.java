package com.ticketsystem.api;

import com.ticketsystem.api.auth.mfa.BreakGlass;
import com.ticketsystem.api.crypto.job.DataKeyRotation;
import com.ticketsystem.api.qa.job.QaCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TicketSystemApplication {

  public static void main(String[] args) {
    if (args.length > 0 && BreakGlass.COMMAND.equals(args[0])) {
      // Break-glass MFA reset (C8): a one-off run without the web server, then exit.
      System.exit(BreakGlass.run(TicketSystemApplication.class, args));
    }
    if (args.length > 0 && DataKeyRotation.COMMAND.equals(args[0])) {
      // Data-key rotation: a one-off run without the web server, then exit.
      System.exit(DataKeyRotation.run(TicketSystemApplication.class, args));
    }
    if (QaCommand.isQaCommand(args)) {
      // QA round set-up and clean-up: a one-off run without the web server, then exit.
      System.exit(QaCommand.run(TicketSystemApplication.class, args));
    }
    SpringApplication.run(TicketSystemApplication.class, args);
  }
}
