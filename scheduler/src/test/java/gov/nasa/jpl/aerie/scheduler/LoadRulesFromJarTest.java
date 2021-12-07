package gov.nasa.jpl.aerie.scheduler;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;

public class LoadRulesFromJarTest {
  private final String nameJar = "merlinsight-rules.jar";

  /**
   * This test needs the merlinsight-rules.jar to be in the test resources folder
   * @throws ClassNotFoundException
   * @throws IOException
   * @throws InvocationTargetException
   * @throws NoSuchMethodException
   * @throws InstantiationException
   * @throws IllegalAccessException
   */
  @Test
  @Disabled
  public void countMerlinSightGoals()
  throws ClassNotFoundException, IOException, InvocationTargetException, InstantiationException
  {
    var path = LoadRulesFromJarTest.class.getClassLoader().getResource(nameJar).getPath();
    //this mimics what would happen when the scheduler is triggered
    Collection<Problem> inst = JarClassLoader.loadProblemsFromJar(path, TestUtility.getMerlinSightMissionModel());
    assert(inst.size()==1);
    var item = inst.iterator().next();
    var goals = item.getGoals();
    assert(goals.size()==23);
  }

}
