package gov.nasa.jpl.aerie.merlin.protocol.model;

import gov.nasa.jpl.aerie.merlin.protocol.driver.Querier;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Optional;

public interface Condition<$Schema> {
  /**
   * POSTCONDITION: The return value `x` satisfies `x.noLaterThan(atLatest)`.
   */
  Optional<Duration> nextSatisfied(Querier<? extends $Schema> now, Duration atLatest);
}
