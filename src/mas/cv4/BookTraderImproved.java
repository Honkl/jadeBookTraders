package mas.cv4;

import jade.content.ContentElement;
import jade.content.lang.Codec;
import jade.content.lang.sl.SLCodec;
import jade.content.onto.Ontology;
import jade.content.onto.OntologyException;
import jade.content.onto.UngroundedException;
import jade.content.onto.basic.Action;
import jade.content.onto.basic.Result;
import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.*;
import jade.domain.FIPAException;
import jade.domain.FIPAService;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.*;
import mas.cv4.onto.*;

import java.util.*;

/**
 * Created by Martin Pilat and modified by Jan Kluj, Jakub Naplava and Ondrej
 * Svec.
 *
 * A more advanced version of the trading agent. The agent tries to buy all
 * books that are in his goals. As for the selling, the non-goal books are sold
 * for the minimal price as specified in Constant class, and the goal books are
 * sold for the price that is higher than it has for me.
 *
 */
public class BookTraderImproved extends Agent {

    Codec codec = new SLCodec();
    Ontology onto = BookOntology.getInstance();

    ArrayList<BookInfo> myBooks;
    ArrayList<Goal> myGoal;
    double myMoney;

    Random rnd = new Random();

    @Override
    protected void setup() {
        super.setup();

        //register the codec and the ontology with the content manager
        this.getContentManager().registerLanguage(codec);
        this.getContentManager().registerOntology(onto);

        //book-trader service description
        ServiceDescription sd = new ServiceDescription();
        sd.setType("book-trader");
        sd.setName("book-trader");

        //description of this agent and the services it provides
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(this.getAID());
        dfd.addServices(sd);

        //register to DF
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            e.printStackTrace();
        }

        //add behavior which waits for the StartTrading message
        addBehaviour(new StartTradingBehaviour(this, MessageTemplate.MatchPerformative(ACLMessage.REQUEST)));
    }

    @Override
    protected void takeDown() {
        super.takeDown();
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    /**
     * Returns books that are in my goals and I do not own them yet.
     *
     * @return
     */
    private static List<BookInfo> getUnsatisfiedGoalBooks(List<Goal> myGoals, List<BookInfo> currentlyPossesedBooks) {
        List<BookInfo> unsatisfiedGoals = new ArrayList<>();
        for (Goal g : myGoals) {
            unsatisfiedGoals.add(g.getBook());
        }
        unsatisfiedGoals.removeAll(currentlyPossesedBooks);

        return unsatisfiedGoals;
    }

    /**
     * Returns my goals that are yet not satisfied.
     *
     * @return
     */
    private static List<Goal> getUnsatisfiedGoals(List<Goal> myGoal, List<BookInfo> myBooks) {
        List<Goal> unsatisfiedGoals = new ArrayList<>();
        unsatisfiedGoals.addAll(myGoal);
        for (Goal goal : myGoal) {
            for (BookInfo myBook : myBooks) {
                if (goal.getBook().getBookName().equals(myBook.getBookName())) {
                    unsatisfiedGoals.remove(goal);
                }
            }
        }

        return unsatisfiedGoals;
    }

    /**
     * Returns the amount of money for which we are willing to sell given book.
     *
     * @param book book, which price to evaluate.
     * @return
     */
    private static double getBookValueSell(BookInfo book, List<Goal> myGoal, List<BookInfo> myBooks) {
        Random rnd = new Random();
        List<Goal> unsatisfiedGoals = getUnsatisfiedGoals(myGoal, myBooks);

        double priceForBook = Constants.bookPrices.get(book.getBookName()) - 20; // @TODO choose wisely

        for (Goal goal : unsatisfiedGoals) {
            if (goal.getBook().getBookName().equals(book.getBookName())) {
                priceForBook = goal.getValue() + rnd.nextInt(10) + 1;
            }
        }

        return priceForBook;
    }

    /**
     * Returns the amount of money for which we are willing to buy given book.
     *
     * @param book book, which price to evaluate.
     * @return
     */
    private static double getBookValueBuy(BookInfo book, List<Goal> myGoal, List<BookInfo> myBooks) {

        List<Goal> unsatisfiedGoals = getUnsatisfiedGoals(myGoal, myBooks);

        //if not in our goals, the book has for us relatively small value
        double priceForBook = Constants.bookPrices.get(book.getBookName()) / 10; // @TODO choose wisely

        for (Goal unsatisfiedGoal : unsatisfiedGoals) {
            //if the book is in our unsatisfied goals and we do not have it yet, than the book has for us quite a high value
            if (unsatisfiedGoal.getBook().getBookName().equals(book.getBookName())) {
                priceForBook = unsatisfiedGoal.getValue() - 10;
            }
        }

        return priceForBook;
    }

    // waits for the StartTrading message and adds the trading behavior
    class StartTradingBehaviour extends AchieveREResponder {

        public StartTradingBehaviour(Agent a, MessageTemplate mt) {
            super(a, mt);
        }

        @Override
        protected ACLMessage handleRequest(ACLMessage request) throws NotUnderstoodException, RefuseException {

            try {
                ContentElement ce = getContentManager().extractContent(request);

                if (!(ce instanceof Action)) {
                    throw new NotUnderstoodException("");
                }
                Action a = (Action) ce;

                //we got the request to start trading
                if (a.getAction() instanceof StartTrading) {

                    //find out what our goals are
                    ACLMessage getMyInfo = new ACLMessage(ACLMessage.REQUEST);
                    getMyInfo.setLanguage(codec.getName());
                    getMyInfo.setOntology(onto.getName());

                    ServiceDescription sd = new ServiceDescription();
                    sd.setType("environment");
                    DFAgentDescription dfd = new DFAgentDescription();
                    dfd.addServices(sd);

                    DFAgentDescription[] envs = DFService.search(myAgent, dfd);

                    getMyInfo.addReceiver(envs[0].getName());
                    getContentManager().fillContent(getMyInfo, new Action(envs[0].getName(), new GetMyInfo()));

                    ACLMessage myInfo = FIPAService.doFipaRequestClient(myAgent, getMyInfo);

                    Result res = (Result) getContentManager().extractContent(myInfo);

                    AgentInfo ai = (AgentInfo) res.getValue();

                    myBooks = ai.getBooks();
                    myGoal = ai.getGoals();
                    myMoney = ai.getMoney();

                    //add a behavior which tries to buy a book every two seconds
                    addBehaviour(new TradingBehaviour(myAgent, 1000)); // @TODO

                    //add a behavior which sells book to other agents
                    addBehaviour(new SellBook(myAgent, MessageTemplate.MatchPerformative(ACLMessage.CFP)));

                    //reply that we are able to start trading (the message is ignored by the environment)
                    ACLMessage reply = request.createReply();
                    reply.setPerformative(ACLMessage.INFORM);
                    return reply;
                }

                throw new NotUnderstoodException("");

            } catch (Codec.CodecException e) {
                e.printStackTrace();
            } catch (OntologyException e) {
                e.printStackTrace();
            } catch (FIPAException e) {
                e.printStackTrace();
            }

            return super.handleRequest(request);
        }

        //this behavior trades with books
        class TradingBehaviour extends TickerBehaviour {

            public TradingBehaviour(Agent a, long period) {
                super(a, period);
            }

            @Override
            protected void onTick() {

                try {
                    //try to make request for all books that are in my goals and I do not own them yet                    
                    List<BookInfo> unsatisfiedGoals = getUnsatisfiedGoalBooks(myGoal, myBooks);
                    for (BookInfo book : unsatisfiedGoals) {

                        //find other seller and prepare a CFP
                        ServiceDescription sd = new ServiceDescription();
                        sd.setType("book-trader");
                        DFAgentDescription dfd = new DFAgentDescription();
                        dfd.addServices(sd);

                        DFAgentDescription[] traders = DFService.search(myAgent, dfd);

                        ACLMessage buyBook = new ACLMessage(ACLMessage.CFP);
                        buyBook.setLanguage(codec.getName());
                        buyBook.setOntology(onto.getName());
                        buyBook.setReplyByDate(new Date(System.currentTimeMillis() + 5000));

                        for (DFAgentDescription dfad : traders) {
                            if (dfad.getName().equals(myAgent.getAID())) {
                                continue;
                            }
                            buyBook.addReceiver(dfad.getName());
                        }

                        ArrayList<BookInfo> bis = new ArrayList<>();

                        BookInfo bi = new BookInfo();
                        bi.setBookName(book.getBookName());
                        bis.add(bi);

                        SellMeBooks smb = new SellMeBooks();
                        smb.setBooks(bis);

                        getContentManager().fillContent(buyBook, new Action(myAgent.getAID(), smb));
                        addBehaviour(new ObtainBook(myAgent, buyBook));

                    }

                } catch (Codec.CodecException | OntologyException | FIPAException e) {
                    e.printStackTrace();
                }

            }
        }

        //this behavior takes care of the buying of the book itself
        class ObtainBook extends ContractNetInitiator {

            public ObtainBook(Agent a, ACLMessage cfp) {
                super(a, cfp);
            }

            Chosen c;  //we need to remember what offer we have chosen
            ArrayList<BookInfo> shouldReceive; //we also remember what the seller offered to us

            //the seller informs us it processed the order, we need to send the payment
            @Override
            protected void handleInform(ACLMessage inform) {
                try {

                    //create the transaction info and send it to the environment
                    MakeTransaction mt = new MakeTransaction();

                    mt.setSenderName(myAgent.getName());
                    mt.setReceiverName(inform.getSender().getName());
                    mt.setTradeConversationID(inform.getConversationId());

                    if (c.getOffer().getBooks() == null) {
                        c.getOffer().setBooks(new ArrayList<BookInfo>());
                    }

                    mt.setSendingBooks(c.getOffer().getBooks());
                    mt.setSendingMoney(c.getOffer().getMoney());

                    if (shouldReceive == null) {
                        shouldReceive = new ArrayList<BookInfo>();
                    }

                    mt.setReceivingBooks(shouldReceive);
                    mt.setReceivingMoney(0.0);

                    ServiceDescription sd = new ServiceDescription();
                    sd.setType("environment");
                    DFAgentDescription dfd = new DFAgentDescription();
                    dfd.addServices(sd);

                    DFAgentDescription[] envs = DFService.search(myAgent, dfd);

                    ACLMessage transReq = new ACLMessage(ACLMessage.REQUEST);
                    transReq.addReceiver(envs[0].getName());
                    transReq.setLanguage(codec.getName());
                    transReq.setOntology(onto.getName());
                    transReq.setReplyByDate(new Date(System.currentTimeMillis() + 5000));

                    getContentManager().fillContent(transReq, new Action(envs[0].getName(), mt));
                    addBehaviour(new SendBook(myAgent, transReq));

                } catch (UngroundedException e) {
                    e.printStackTrace();
                } catch (OntologyException e) {
                    e.printStackTrace();
                } catch (Codec.CodecException e) {
                    e.printStackTrace();
                } catch (FIPAException e) {
                    e.printStackTrace();
                }

            }

            //process the offers from the sellers
            @Override
            protected void handleAllResponses(Vector responses, Vector acceptances) {
                /* 
                 We obtained several offers and should choose only one, which we accept. 
                 The rest must be refused.                
                 */

                Iterator it = responses.iterator();

                //offer, which is currently best for me
                Offer currentBestOffer = null;
                //its utility
                double currentBestOfferValue = Double.MIN_VALUE;
                //and its index in responses.iterator() (when not couting "REFUSE" responses)
                int currentBestOfferIndex = -1;

                //all non "REFUSE" responses
                List<ACLMessage> responseList = new ArrayList<>();

                int index = -1;
                while (it.hasNext()) {
                    ACLMessage response = (ACLMessage) it.next();

                    ContentElement ce = null;
                    try {
                        if (response.getPerformative() == ACLMessage.REFUSE) {
                            //System.out.println("Enemy refused");
                            continue;
                        }

                        index++;
                        responseList.add(response);

                        ce = getContentManager().extractContent(response);

                        ChooseFrom cf = (ChooseFrom) ce;

                        ArrayList<Offer> offers = cf.getOffers();

                        /*
                         Foreach offer, check whether we can fulfill it(we have all requested books and enough money).
                         And if we can, then compute its utility (how much would we gain, when accepting it).
                         The best offer is than saved.
                         */
                        for (Offer o : offers) {
                            //we must have enough money
                            if (o.getMoney() > myMoney) {
                                continue;
                            }

                            //and all requested books
                            boolean foundAll = true;
                            if (o.getBooks() != null) {
                                for (BookInfo bi : o.getBooks()) {
                                    String bn = bi.getBookName();
                                    boolean found = false;
                                    for (int j = 0; j < myBooks.size(); j++) {
                                        if (myBooks.get(j).getBookName().equals(bn)) {
                                            found = true;
                                            bi.setBookID(myBooks.get(j).getBookID());
                                            break;
                                        }
                                    }
                                    if (!found) {
                                        foundAll = false;
                                        break;
                                    }
                                }
                            }

                            if (foundAll) {
                                double offerUtility = getOfferUtility(o, cf.getWillSell());

                                //if this offer is currently best for us, save it
                                if (currentBestOffer == null || currentBestOfferValue < offerUtility) {
                                    currentBestOffer = o;
                                    currentBestOfferValue = offerUtility;
                                    currentBestOfferIndex = index;
                                }
                            }
                        }
                    } catch (Codec.CodecException e) {
                        e.printStackTrace();
                    } catch (OntologyException e) {
                        e.printStackTrace();
                    }
                }

                /* DEBUGGING best offer
                 System.out.println("=============");
                 System.out.println("Number of offers:" + index + " , current best offer index"
                 + currentBestOfferIndex + "current best offer value:" + currentBestOfferValue);
                 if (currentBestOffer != null) {
                 System.out.println("This offer included: wanted money:" + currentBestOffer.getMoney());
                 try {
                 System.out.println("Wanted book:" + ((ChooseFrom) getContentManager().extractContent(responseList.get(currentBestOfferIndex))).getWillSell().get(0).getBookName());
                 } catch (Codec.CodecException | OntologyException ex) {
                 Logger.getLogger(BookTraderImproved.class.getName()).log(Level.SEVERE, null, ex);
                 }
                 if (currentBestOffer.getBooks() != null) {
                 for (BookInfo book : currentBestOffer.getBooks()) {
                 System.out.println("  requested book:" + book.getBookName());
                 }
                 }
                 }
                 System.out.println("-----------");
                 */
                //foreach non "REFUSE" proposal, we either accept it (when it's the best offer), or refuse it
                for (int i = 0; i <= index; i++) {
                    try {
                        ACLMessage response = responseList.get(i);

                        //when it is the best offer and we will not lose money on this offer
                        if (i == currentBestOfferIndex && currentBestOfferValue > 0) {

                            ACLMessage acc = response.createReply();
                            acc.setPerformative(ACLMessage.ACCEPT_PROPOSAL);

                            Chosen ch = new Chosen();
                            ch.setOffer(currentBestOffer);

                            ChooseFrom cf = (ChooseFrom) getContentManager().extractContent(response);
                            c = ch;
                            shouldReceive = cf.getWillSell();

                            //System.out.println(myAgent.getName() + " buying for " + c.getOffer().getMoney() + " books: " + shouldReceive.get(0).getBookName());
                            getContentManager().fillContent(acc, ch);
                            acceptances.add(acc);
                        } else {
                            ACLMessage acc = response.createReply();
                            acc.setPerformative(ACLMessage.REJECT_PROPOSAL);
                            acceptances.add(acc);
                        }
                    } catch (Codec.CodecException e) {
                        e.printStackTrace();
                    } catch (OntologyException e) {
                        e.printStackTrace();
                    }
                }
            }

        }

        /**
         * Computes the money gain when making transaction with the given offer.
         *
         * @param offer offer to evaluate
         * @param offeredBooks books that were offered to us
         * @return utility computed as (myGain - myLoss)
         */
        private double getOfferUtility(Offer offer, List<BookInfo> offeredBooks) {
            double requestedMoney = offer.getMoney();
            List<BookInfo> requestedBooks = offer.getBooks();

            double myLoss = requestedMoney;
            if (requestedBooks != null) {
                for (BookInfo requestedBook : requestedBooks) {
                    myLoss += getBookValueSell(requestedBook, myGoal, myBooks);
                }
            }

            double myGain = 0;
            if (offeredBooks != null) {
                for (BookInfo offeredBook : offeredBooks) {
                    myGain += getBookValueBuy(offeredBook, myGoal, myBooks);
                }
            }
            //System.out.println("Utility for " + offeredBooks.get(0).getBookName() + "is "  + (myGain - myLoss) );
            return (myGain - myLoss);
        }
    }

    //this behavior processes the selling of books
    class SellBook extends SSResponderDispatcher {

        public SellBook(Agent a, MessageTemplate tpl) {
            super(a, tpl);
        }

        @Override
        protected Behaviour createResponder(ACLMessage initiationMsg) {
            return new SellBookResponder(myAgent, initiationMsg);
        }
    }

    class SellBookResponder extends SSContractNetResponder {

        public SellBookResponder(Agent a, ACLMessage cfp) {
            super(a, cfp);
        }

        @Override
        protected ACLMessage handleCfp(ACLMessage cfp) throws RefuseException, FailureException, NotUnderstoodException {

            try {
                Action ac = (Action) getContentManager().extractContent(cfp);

                SellMeBooks smb = (SellMeBooks) ac.getAction();
                ArrayList<BookInfo> books = smb.getBooks();
                ArrayList<BookInfo> sellBooks = new ArrayList<>();

                //find out, if we have books the agent wants
                for (int i = 0; i < books.size(); i++) {
                    boolean found = false;
                    for (int j = 0; j < myBooks.size(); j++) {
                        if (myBooks.get(j).getBookName().equals(books.get(i).getBookName())) {
                            sellBooks.add(myBooks.get(j));
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        throw new RefuseException("");
                    }
                }

                ArrayList<Offer> offers = new ArrayList<>();
                double sellPrice = 0;
                for (BookInfo toSell : sellBooks) {
                    sellPrice += getBookValueSell(toSell, myGoal, myBooks);
                }

                //System.out.println(myAgent.getName() + " offering for " + sellPrice + " books: " + sellBooks.stream().map(Object::toString).collect(Collectors.joining(" ")));
                Offer offer = new Offer();
                offer.setMoney(sellPrice);
                offers.add(offer);

                // book-for-book, book+money offers
                for (Goal g : getUnsatisfiedGoals(myGoal, myBooks)) {
                    ArrayList<BookInfo> bis = new ArrayList<>();
                    bis.add(g.getBook());

                    Offer o = new Offer();
                    o.setBooks(bis);
                    double requiredMoney = Math.max(0, sellPrice - g.getValue());
                    o.setMoney(requiredMoney);
                    offers.add(o);
                }
                ChooseFrom cf = new ChooseFrom();

                cf.setWillSell(sellBooks);
                cf.setOffers(offers);

                //send the offers
                ACLMessage reply = cfp.createReply();
                reply.setPerformative(ACLMessage.PROPOSE);
                reply.setReplyByDate(new Date(System.currentTimeMillis() + 5000));
                getContentManager().fillContent(reply, cf);

                return reply;
            } catch (UngroundedException e) {
                e.printStackTrace();
            } catch (Codec.CodecException e) {
                e.printStackTrace();
            } catch (OntologyException e) {
                e.printStackTrace();
            }

            throw new FailureException("");
        }

        //the buyer decided to accept an offer
        @Override
        protected ACLMessage handleAcceptProposal(ACLMessage cfp, ACLMessage propose, ACLMessage accept) throws FailureException {

            try {
                ChooseFrom cf = (ChooseFrom) getContentManager().extractContent(propose);

                //prepare the transaction info and send it to the environment
                MakeTransaction mt = new MakeTransaction();

                mt.setSenderName(myAgent.getName());
                mt.setReceiverName(cfp.getSender().getName());
                mt.setTradeConversationID(cfp.getConversationId());

                if (cf.getWillSell() == null) {
                    cf.setWillSell(new ArrayList<BookInfo>());
                }

                mt.setSendingBooks(cf.getWillSell());
                mt.setSendingMoney(0.0);

                Chosen c = (Chosen) getContentManager().extractContent(accept);

                if (c.getOffer().getBooks() == null) {
                    c.getOffer().setBooks(new ArrayList<BookInfo>());
                }

                mt.setReceivingBooks(c.getOffer().getBooks());
                mt.setReceivingMoney(c.getOffer().getMoney());

                ServiceDescription sd = new ServiceDescription();
                sd.setType("environment");
                DFAgentDescription dfd = new DFAgentDescription();
                dfd.addServices(sd);

                DFAgentDescription[] envs = DFService.search(myAgent, dfd);

                ACLMessage transReq = new ACLMessage(ACLMessage.REQUEST);
                transReq.addReceiver(envs[0].getName());
                transReq.setLanguage(codec.getName());
                transReq.setOntology(onto.getName());
                transReq.setReplyByDate(new Date(System.currentTimeMillis() + 5000));

                getContentManager().fillContent(transReq, new Action(envs[0].getName(), mt));

                addBehaviour(new SendBook(myAgent, transReq));

                ACLMessage reply = accept.createReply();
                reply.setPerformative(ACLMessage.INFORM);
                return reply;

            } catch (UngroundedException e) {
                e.printStackTrace();
            } catch (OntologyException e) {
                e.printStackTrace();
            } catch (Codec.CodecException e) {
                e.printStackTrace();
            } catch (FIPAException e) {
                e.printStackTrace();
            }

            throw new FailureException("");
        }
    }

    //after the transaction is complete (the environment returned an INFORM), we update our information
    class SendBook extends AchieveREInitiator {

        public SendBook(Agent a, ACLMessage msg) {
            super(a, msg);
        }

        @Override
        protected void handleInform(ACLMessage inform) {

            try {
                ACLMessage getMyInfo = new ACLMessage(ACLMessage.REQUEST);
                getMyInfo.setLanguage(codec.getName());
                getMyInfo.setOntology(onto.getName());

                ServiceDescription sd = new ServiceDescription();
                sd.setType("environment");
                DFAgentDescription dfd = new DFAgentDescription();
                dfd.addServices(sd);

                DFAgentDescription[] envs = DFService.search(myAgent, dfd);

                getMyInfo.addReceiver(envs[0].getName());
                getContentManager().fillContent(getMyInfo, new Action(envs[0].getName(), new GetMyInfo()));

                ACLMessage myInfo = FIPAService.doFipaRequestClient(myAgent, getMyInfo);

                Result res = (Result) getContentManager().extractContent(myInfo);

                AgentInfo ai = (AgentInfo) res.getValue();

                myBooks = ai.getBooks();
                myGoal = ai.getGoals();
                myMoney = ai.getMoney();
            } catch (OntologyException e) {
                e.printStackTrace();
            } catch (FIPAException e) {
                e.printStackTrace();
            } catch (Codec.CodecException e) {
                e.printStackTrace();
            }

        }
    }
}
