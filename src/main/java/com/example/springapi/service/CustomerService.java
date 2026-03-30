package com.example.springapi.service;

import com.example.springapi.dto.CreateCustomerRequest;
import com.example.springapi.dto.CustomerDto;
import com.example.springapi.model.Customer;
import com.example.springapi.repository.CustomerRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomerService {

    private final CustomerRepository repository;
    private final RecentCustomerBuffer recentCustomerBuffer;

    public CustomerService(CustomerRepository repository, RecentCustomerBuffer recentCustomerBuffer) {
        this.repository = repository;
        this.recentCustomerBuffer = recentCustomerBuffer;
    }

    public List<CustomerDto> findAll() {
        return repository.findAll()
                .stream()
                .map(this::toDto)
                .toList();
    }

    public CustomerDto create(CreateCustomerRequest request) {
        Customer customer = new Customer();
        customer.setName(request.name());
        customer.setEmail(request.email());

        Customer saved = repository.save(customer);
        CustomerDto dto = toDto(saved);
        recentCustomerBuffer.add(dto);
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
