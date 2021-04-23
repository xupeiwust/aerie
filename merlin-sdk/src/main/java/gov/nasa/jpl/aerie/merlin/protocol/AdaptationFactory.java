package gov.nasa.jpl.aerie.merlin.protocol;

import gov.nasa.jpl.aerie.merlin.timeline.Query;
import gov.nasa.jpl.aerie.merlin.timeline.effects.Applicator;
import gov.nasa.jpl.aerie.merlin.timeline.effects.Projection;

public interface AdaptationFactory {
  Adaptation<?> instantiate(SerializedValue configuration);
  <$Schema> void instantiate(SerializedValue configuration, Builder<$Schema> builder);

  interface Builder<$Schema> {
    boolean isBuilt();

    <Event, Effect, CellType>
    Query<$Schema, Event, CellType>
    allocate(final Projection<Event, Effect> projection, final Applicator<Effect, CellType> applicator);

    String daemon(TaskFactory<$Schema> factory);

    <Activity> void taskSpecType(String name, TaskSpecType<$Schema, Activity> taskSpecType);

    <Dynamics> void resourceFamily(ResourceFamily<$Schema, Dynamics> resourceFamily);
  }

  interface TaskFactory<$Schema> {
    <$Timeline extends $Schema> Task<$Timeline> create();
  }
}
