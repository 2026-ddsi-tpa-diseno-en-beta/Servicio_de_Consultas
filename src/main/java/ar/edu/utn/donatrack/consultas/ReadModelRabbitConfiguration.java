package ar.edu.utn.donatrack.consultas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "donatrack.read-model.messaging.enabled", havingValue = "true")
public class ReadModelRabbitConfiguration {
  @Bean
  public MessageConverter readModelMessageConverter(ObjectMapper mapper) {
    return new Jackson2JsonMessageConverter(mapper);
  }

  @Bean
  public TopicExchange readModelExchange() {
    return new TopicExchange("donatrack.readmodel.exchange", true, false);
  }

  @Bean
  public Queue readModelQueue(
      @Value("${donatrack.read-model.queue:donatrack.readmodel.v1.events}") String name) {
    return QueueBuilder.durable(name)
        .deadLetterExchange("donatrack.readmodel.dlx")
        .deadLetterRoutingKey("failed")
        .build();
  }

  @Bean
  public Binding readModelBinding(
      @org.springframework.beans.factory.annotation.Qualifier("readModelQueue")
          Queue readModelQueue,
      TopicExchange readModelExchange) {
    return BindingBuilder.bind(readModelQueue).to(readModelExchange).with("readmodel.changed");
  }

  @Bean
  public org.springframework.amqp.core.DirectExchange readModelDlx() {
    return new org.springframework.amqp.core.DirectExchange("donatrack.readmodel.dlx", true, false);
  }

  @Bean
  public Queue readModelDlq() {
    return QueueBuilder.durable("donatrack.readmodel.dlq").build();
  }

  @Bean
  public Binding readModelDlqBinding() {
    return BindingBuilder.bind(readModelDlq()).to(readModelDlx()).with("failed");
  }
}
