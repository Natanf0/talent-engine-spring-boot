package br.com.careermatcher.services;

import br.com.careermatcher.DTOs.CompanyDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MessagingServiceImp {
    private final RabbitTemplate rabbitTemplate;

    public void sendCreateCompanyMessage(CompanyDTO companyDTO) {
        rabbitTemplate.convertAndSend("create", "createCompany", companyDTO);
    }
}
