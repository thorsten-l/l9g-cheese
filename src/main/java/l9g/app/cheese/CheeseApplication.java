package l9g.app.cheese;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

@SpringBootApplication
@ImportRuntimeHints(CaffeineRuntimeHints.class)
public class CheeseApplication
{

  public static void main(String[] args)
  {
    SpringApplication.run(CheeseApplication.class, args);
  }

}
