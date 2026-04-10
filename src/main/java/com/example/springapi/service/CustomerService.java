package com.example.springapi.service;

import com.example.springapi.dto.CreateCustomerRequest;
import com.example.springapi.dto.CustomerDto;
import com.example.springapi.event.CustomerCreatedEvent;
import com.example.springapi.model.Customer;
import com.example.springapi.repository.CustomerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class CustomerService {

    private final CustomerRepository repository;
    private final RecentCustomerBuffer recentCustomerBuffer;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String customerCreatedTopic;

    public CustomerService(CustomerRepository repository,
                           RecentCustomerBuffer recentCustomerBuffer,
                           KafkaTemplate<String, Object> kafkaTemplate,
                           @Value("${app.kafka.topics.customer-created}") String customerCreatedTopic) {
        this.repository = repository;
        this.recentCustomerBuffer = recentCustomerBuffer;
        this.kafkaTemplate = kafkaTemplate;
        this.customerCreatedTopic = customerCreatedTopic;
    }

    public List<CustomerDto> findAll() {
        return repository.findAll()
                .stream()
                .map(this::toDto)
                .toList();
    }

    public Optional<CustomerDto> findById(Long id) {
        return repository.findById(id).map(this::toDto);
    }

    public CustomerDto create(CreateCustomerRequest request) {
        Customer customer = new Customer();
        customer.setName(request.name());
        customer.setEmail(request.email());

        Customer saved = repository.save(customer);
        CustomerDto dto = toDto(saved);
        recentCustomerBuffer.add(dto);

        // Pattern 1 — async: publish event, do not wait for consumer
        kafkaTemplate.send(customerCreatedTopic,
                String.valueOf(saved.getId()),
                new CustomerCreatedEvent(saved.getId(), saved.getName(), saved.getEmail()));

        return dto;
    }

    private CustomerDto toDto(Customer customer) {
        return new CustomerDto(
                customer.getId(),
                customer.getName(),
                customer.getEmail()
        );
    }
}
