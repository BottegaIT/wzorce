/*
 * Copyright 2011-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package pl.com.bottega.ecommerce.sales.application.impl;

import javax.inject.Inject;

import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import pl.com.bottega.ddd.annotations.application.ApplicationService;
import pl.com.bottega.ecommerce.canonicalmodel.publishedlanguage.AggregateId;
import pl.com.bottega.ecommerce.sales.application.api.command.OrderDetailsCommand;
import pl.com.bottega.ecommerce.sales.application.api.service.OfferChangedExcpetion;
import pl.com.bottega.ecommerce.sales.application.api.service.OrderingService;
import pl.com.bottega.ecommerce.sales.domain.client.Client;
import pl.com.bottega.ecommerce.sales.domain.client.ClientRepository;
import pl.com.bottega.ecommerce.sales.domain.equivalent.SuggestionService;
import pl.com.bottega.ecommerce.sales.domain.offer.DiscountFactory;
import pl.com.bottega.ecommerce.sales.domain.offer.DiscountPolicy;
import pl.com.bottega.ecommerce.sales.domain.offer.Offer;
import pl.com.bottega.ecommerce.sales.domain.payment.Payment;
import pl.com.bottega.ecommerce.sales.domain.payment.PaymentRepository;
import pl.com.bottega.ecommerce.sales.domain.productscatalog.Product;
import pl.com.bottega.ecommerce.sales.domain.productscatalog.ProductRepository;
import pl.com.bottega.ecommerce.sales.domain.purchase.Purchase;
import pl.com.bottega.ecommerce.sales.domain.purchase.PurchaseFactory;
import pl.com.bottega.ecommerce.sales.domain.purchase.PurchaseRepository;
import pl.com.bottega.ecommerce.sales.domain.reservation.Reservation;
import pl.com.bottega.ecommerce.sales.domain.reservation.ReservationFactory;
import pl.com.bottega.ecommerce.sales.domain.reservation.ReservationRepository;
import pl.com.bottega.ecommerce.sharedkernel.exceptions.DomainOperationException;
import pl.com.bottega.ecommerce.system.application.SystemContext;

/**
 * 
 * @author Slawek
 */
@ApplicationService
public class OrderingServiceImpl implements OrderingService {

	@Inject
	private SystemContext systemContext;
	
	@Inject
	private ClientRepository clientRepository;

	@Inject
	private ReservationRepository reservationRepository;

	@Inject
	private ReservationFactory reservationFactory;

	@Inject
	private PurchaseFactory purchaseFactory;

	@Inject
	private PurchaseRepository purchaseRepository;
	
	@Inject
	private ProductRepository productRepository;
	
	@Inject 
	private PaymentRepository paymentRepository;

	@Inject
	private DiscountFactory discountFactory;
	
	@Inject
	private SuggestionService suggestionService;

	// @Secured requires BUYER role
	public AggregateId createOrder() {
		Reservation reservation = reservationFactory.create(loadClient());
		reservationRepository.save(reservation);
		return reservation.getAggregateId();
	}

	@Override
	public void addProduct(AggregateId orderId, AggregateId productId,
			int quantity) {
		Reservation reservation = reservationRepository.load(orderId);
		
		Product product = productRepository.load(productId);
		
		if (! product.isAvailabe()){
			Client client = loadClient();	
			product = suggestionService.suggestEquivalent(product, client);
		}
			
		reservation.add(product, quantity);
		
		reservationRepository.save(reservation);
	}
	
	public Offer calculateOffer(AggregateId orderId) {
		Reservation reservation = reservationRepository.load(orderId);

		DiscountPolicy discountPolicy = discountFactory.create(loadClient());
		
		/*
		 * Sample pattern: Aggregate generates Value Object using function<br>
		 * Higher order function is closured by policy
		 */
		return reservation.calculateOffer(discountPolicy);
	}

	@Override
	@Transactional(isolation = Isolation.SERIALIZABLE)//highest isolation needed because of manipulating many Aggregates
	public void confirm(AggregateId orderId, OrderDetailsCommand orderDetailsCommand, Offer seenOffer)
			throws OfferChangedExcpetion {
		Reservation reservation = reservationRepository.load(orderId);
		if (reservation.isClosed())
			throw new DomainOperationException(reservation.getAggregateId(), "reservation is already closed");
		
		/*
		 * Sample pattern: Aggregate generates Value Object using function<br>
		 * Higher order function is closured by policy
		 */
		Offer newOffer = reservation.calculateOffer(
									discountFactory.create(loadClient()));
		
		/*
		 * Sample pattern: Client Tier sends back old VOs, Server generates new VOs based on Aggregate state<br>
		 * Notice that this VO is not stored in Repo, it's stored on the Client Tier. 
		 */
		if (! newOffer.sameAs(seenOffer, 5))//TODO load delta from conf.
			throw new OfferChangedExcpetion(reservation.getAggregateId(), seenOffer, newOffer);
		
		Client client = loadClient();//create per logged client, not reservation owner					
		Purchase purchase = purchaseFactory.create(reservation.getAggregateId(), client, seenOffer);
				
		if (! client.canAfford(purchase.getTotalCost()))
			throw new DomainOperationException(client.getAggregateId(), "client has insufficent money");
		
		purchaseRepository.save(purchase);//Aggregate must be managed by persistence context before firing events (synchronous listeners may need to load it) 
		
		/*
		 * Sample model where one aggregate creates another. Client does not manage payment lifecycle, therefore application must manage it. 
		 */
		Payment payment = client.charge(purchase.getTotalCost());
		paymentRepository.save(payment);
		
		purchase.confirm();	
		reservation.close();				
		
		reservationRepository.save(reservation);
		clientRepository.save(client);
		
	}
	
	private Client loadClient() {
		return clientRepository.load(systemContext.getSystemUser().getClientId());
	}
}
