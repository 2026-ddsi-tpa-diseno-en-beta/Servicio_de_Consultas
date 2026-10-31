package ar.edu.utn.donatrack.consultas;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

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
    public Queue readModelQueue(@Value("${donatrack.read-model.queue:donatrack.readmodel.events}") String name) {
        return QueueBuilder.durable(name).build();
    }

    @Bean
    public Binding readModelBinding(Queue readModelQueue, TopicExchange readModelExchange) {
        return BindingBuilder.bind(readModelQueue).to(readModelExchange).with("readmodel.changed");
    }
}
