package mas.cv4;

import jade.content.ContentElement;
import jade.content.lang.Codec;
import jade.content.lang.sl.SLCodec;
import jade.content.onto.Ontology;
import jade.content.onto.OntologyException;
import jade.content.onto.basic.Action;
import jade.content.onto.basic.Result;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.AchieveREInitiator;
import mas.cv4.onto.*;
import mas.cv4.onto.BookInfo;
import mas.cv4.onto.BookOntology;

import java.util.*;

/**
 * Created by Martin Pilat on 11.2.14.
 *
 * An implementation of the environment which takes care of the trading between teh agents, send books and stores
 * information about agents.
 */
public class Environment extends Agent {

    ArrayList<String> agents = new ArrayList<String>();
    HashMap<String, AgentInfo> agentBooks = new HashMap<String, AgentInfo>();
    HashMap<String, TransactionInfo> unfinishedTransaction = new HashMap<String, TransactionInfo>();
    Codec codec = new SLCodec();
    Ontology onto = BookOntology.getInstance();
    Random rnd = new Random();


    @Override
    protected void setup() {
        super.setup();

        //register the codec and the ontology with the content manager
        this.getContentManager().registerLanguage(codec);
        this.getContentManager().registerOntology(onto);

        //describe the environment service
        ServiceDescription sd = new ServiceDescription();
        sd.setType("environment");
        sd.setName("env");

        //describe this agents and the services it provides
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(this.getAID());
        dfd.addServices(sd);

        //register with DF
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            e.printStackTrace();
        }

        //send StartTrading to all agents
        addBehaviour(new StartTradingBehavior());
        //periodically print the utilities of all agents
        addBehaviour(new PrintAgentUtilityBehaviour(this));
        //process all incoming requests
        addBehaviour(new MessageDispatcherBehavior());
        //periodically remove all transactions which were not completed in time
        addBehaviour(new UnfinishedTransactionsRemoverBehavior(this));

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

    //send the info about the start of trading, generate goals for agents
    private class StartTradingBehavior extends OneShotBehaviour {

        @Override
        public void action() {

            //find all traders
            ServiceDescription sd = new ServiceDescription();
            sd.setType("book-trader");
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.addServices(sd);

            try {
                DFAgentDescription[] traders = DFService.search(myAgent, dfd);

                ACLMessage startMsg = new ACLMessage(ACLMessage.REQUEST);
                startMsg.setOntology(onto.getName());
                startMsg.setLanguage(codec.getName());

                ArrayList<String> booksNames = new ArrayList<String>();
                booksNames.addAll(Constants.getBooknames());
                int bID = 0;

                //generate goals and books for each agent
                for (DFAgentDescription tr : traders) {

                    Collections.shuffle(booksNames,rnd);
                    AgentInfo ai = new AgentInfo();
                    ArrayList<BookInfo> books = new ArrayList<BookInfo>();
                    ArrayList<Goal> goal = new ArrayList<Goal>();


                    for (int i = 0; i < 4; i++) {
                        BookInfo bi = new BookInfo();
                        bi.setBookID(bID++);
                        bi.setBookName(booksNames.get(i));
                        books.add(bi);
                    }

                    for (int i = 4; i < booksNames.size(); i++) {
                        BookInfo bi = new BookInfo();
                        bi.setBookID(bID++);
                        bi.setBookName(booksNames.get(i));
                        Goal g = new Goal();
                        g.setBook(bi);
                        g.setValue(Constants.getPrice(booksNames.get(i))+rnd.nextInt(40)-20);
                        goal.add(g);
                    }

                    ai.setBooks(books);
                    ai.setGoals(goal);
                    ai.setMoney(400);

                    System.out.println("Created goals for: " + tr.getName().getName());
                    System.out.println("\t" + ai.toString());
                    agentBooks.put(tr.getName().getName(), ai);
                    startMsg.addReceiver(tr.getName());
                }


                getContentManager().fillContent(startMsg, new Action(myAgent.getAID(), new StartTrading()));

                addBehaviour(new AchieveREInitiator(myAgent, startMsg));

            } catch (FIPAException e) {
                e.printStackTrace();
            } catch (Codec.CodecException e) {
                e.printStackTrace();
            } catch (OntologyException e) {
                e.printStackTrace();
            }

        }
    }

    //print the utility of all agents every 15 seconds
    private class PrintAgentUtilityBehaviour extends TickerBehaviour {

        public PrintAgentUtilityBehaviour(Agent myAgent) {
            super(myAgent, 15000);
        }

        private class AgentUtil implements Comparable<AgentUtil> {

            public String agent;
            public double util;
            public boolean goalMet;

            public AgentUtil(String agent, double util, boolean goalMet) {
                this.agent = agent;
                this.util = util;
                this.goalMet = goalMet;
            }

            @Override
            public int compareTo(AgentUtil o) {
                return Double.compare(this.util, o.util);
            }
        }

        @Override
        protected void onTick() {

            ArrayList<AgentUtil> utils = new ArrayList<AgentUtil>();

            for (String aid : agentBooks.keySet()) {
                AgentInfo ai = agentBooks.get(aid);
                double util = Utils.computeUtility(ai);
                boolean allGoals = Utils.hasAllBooks(ai);
                utils.add(new AgentUtil(aid, util, allGoals));
            }

            Collections.sort(utils);

            System.out.println();
            System.out.println();
            for (int i = utils.size() - 1; i >=0; i--) {
                AgentUtil au = utils.get(i);
                System.out.printf("%50s  %13f %5s \n", au.agent, au.util, au.goalMet ? "YES" : "NO");
                System.out.println(agentBooks.get(au.agent));
            }
            System.out.println();

        }
    }

    //remove unmatched transactions older than 5 seconds every 5 seconds
    private class UnfinishedTransactionsRemoverBehavior extends TickerBehaviour {

        public UnfinishedTransactionsRemoverBehavior(Agent myAgent) {
            super(myAgent, 5000);
        }

        @Override
        protected void onTick() {

            ArrayList<String> remove = new ArrayList<String>();

            long time = System.currentTimeMillis();
            for (TransactionInfo ti : unfinishedTransaction.values()) {
                if (ti.getTimeReceived()-time > 5000)
                    remove.add(ti.getSendOrder().getTradeConversationID());
            }

            for (String s : remove) {

                ACLMessage fail = unfinishedTransaction.get(s).getSenderMessage().createReply();
                fail.setPerformative(ACLMessage.FAILURE);
                fail.setContent("transaction not matched in time");
                send(fail);
                unfinishedTransaction.remove(s);
            }

        }
    }

    //dispatch the requests to two behaviros which will take care of them
    private class MessageDispatcherBehavior extends CyclicBehaviour {

        @Override
        public void action() {

            ACLMessage received = myAgent.receive(MessageTemplate.MatchPerformative(ACLMessage.REQUEST));

            if (received == null) {
                block();
                return;
            }

            ContentElement ce = null;
            try {
                ce = myAgent.getContentManager().extractContent(received);
            } catch (Codec.CodecException e) {
                e.printStackTrace();
            } catch (OntologyException e) {
                e.printStackTrace();
            }

            if (!(ce instanceof Action)) {
                System.err.println("Unexpected message: " + received.getContent());
            }

            Action aa = (Action)ce;

            //add behavior for MakeTransaction
            if (aa.getAction() instanceof MakeTransaction) {
                myAgent.addBehaviour(new HandleSendBehaviour(myAgent, (MakeTransaction)aa.getAction(), received));
            }

            //add behavior for GetMyInfo
            if (aa.getAction() instanceof GetMyInfo) {
                myAgent.addBehaviour(new HandleInfoBehaviour(myAgent, (GetMyInfo)aa.getAction(), received));
            }

        }
    }

    //sends the info about the agent who requests it
    private class HandleInfoBehaviour extends OneShotBehaviour {

        Agent myAgent;
        GetMyInfo gmi;
        ACLMessage request;

        public HandleInfoBehaviour(Agent myAgent, GetMyInfo gmi, ACLMessage request) {
            this.myAgent = myAgent;
            this.gmi = gmi;
            this.request = request;
        }

        @Override
        public void action() {

            //System.out.println("Got info request from " + request.getSender().getName());

            ACLMessage reply = request.createReply();

            String agentName = request.getSender().getName();

            //get the information
            AgentInfo ai = agentBooks.get(agentName);

            if (ai == null) {
                reply.setPerformative(ACLMessage.FAILURE);
                reply.setContent("agent not found");
                send(reply);
                return;
            }

            //send it to the agent
            reply.setPerformative(ACLMessage.INFORM);
            try {
                getContentManager().fillContent(reply, new Result(gmi, ai));
            } catch (Codec.CodecException e) {
                e.printStackTrace();
            } catch (OntologyException e) {
                e.printStackTrace();
            }

            myAgent.send(reply);
        }
    }


    //process the transaction between two agents
    private class HandleSendBehaviour extends OneShotBehaviour {

        MakeTransaction sendMsgContent;
        ACLMessage sendMsg;

        private HandleSendBehaviour(Agent a, MakeTransaction sendMsgContent, ACLMessage sendMsg ) {
            super(a);
            this.sendMsgContent = sendMsgContent;
            this.sendMsg = sendMsg;
        }

        @Override
        public void action() {

            String transactionID = sendMsgContent.getTradeConversationID();


            //we got request from one agent, remember the transaction
            if (!unfinishedTransaction.containsKey(transactionID)) { //this is the first time we know about transaction
                unfinishedTransaction.put(transactionID, new TransactionInfo(sendMsgContent, sendMsg, System.currentTimeMillis()));
                return;
            }

            //we got request from the other agent
            ACLMessage sendMsg1 = unfinishedTransaction.get(transactionID).getSenderMessage();
            ACLMessage sendMsg2 = sendMsg;

            MakeTransaction sendOrder1 = unfinishedTransaction.get(transactionID).getSendOrder();
            MakeTransaction sendOrder2 = sendMsgContent;


            //check the senders and receiver match
            if (!sendOrder1.getReceiverName().equals(sendOrder2.getSenderName()) ||
                    !sendOrder1.getSenderName().equals(sendOrder2.getReceiverName())) {

                sendFailure(sendMsg1, sendMsg2, "sender and receiver do not match");
            }

            String agentName1 = sendOrder1.getSenderName();
            String agentName2 = sendOrder2.getSenderName();

            AgentInfo agentInfo1 = agentBooks.get(agentName1);
            AgentInfo agentInfo2 = agentBooks.get(agentName2);

            //check the agents have all the books they want to send
            ArrayList<BookInfo> ag1MissingBooks = getMissingBooks(agentInfo1, sendOrder1.getSendingBooks());
            if (ag1MissingBooks.size() > 0) {
                sendFailure(sendMsg1, sendMsg2, agentName1 + " does not have " + ag1MissingBooks);
                return;
            }

            ArrayList<BookInfo> ag2MissingBooks = getMissingBooks(agentInfo2, sendOrder2.getSendingBooks());
            if (ag2MissingBooks.size() > 0) {
                sendFailure(sendMsg1, sendMsg2, agentName2 + " does not have " + ag2MissingBooks);
                return;
            }

            //check the agent have enough money
            if (agentInfo1.getMoney() < sendOrder1.getSendingMoney()) {
                sendFailure(sendMsg1, sendMsg2, agentName1 + " does not have enough money");
                return;
            }

            if (agentInfo2.getMoney() < sendOrder2.getSendingMoney()) {
                sendFailure(sendMsg1, sendMsg2, agentName2 + " does not have enough money");
                return;
            }

            //check the list of sent and expected books match
            if (sendOrder1.getSendingBooks() != null && sendOrder2.getReceivingBooks() != null)
                if (sendOrder1.getSendingBooks().size() != sendOrder2.getReceivingBooks().size()) {
                    sendFailure(sendMsg1, sendMsg2, "orders do not match");
                    return;
                }

            if (sendOrder2.getSendingBooks() != null && sendOrder1.getReceivingBooks() != null)
                if (sendOrder1.getReceivingBooks().size() != sendOrder2.getSendingBooks().size()) {
                    sendFailure(sendMsg1, sendMsg2, "orders do not match");
                    return;
                }

            for (int i = 0; i < sendOrder1.getSendingBooks().size(); i++) {
                if (sendOrder1.getSendingBooks().get(i).getBookID() != sendOrder2.getReceivingBooks().get(i).getBookID()) {
                    sendFailure(sendMsg1, sendMsg2, "orders do not match");
                    return;
                }
            }

            for (int i = 0; i < sendOrder2.getSendingBooks().size(); i++) {
                if (sendOrder2.getSendingBooks().get(i).getBookID() != sendOrder1.getReceivingBooks().get(i).getBookID()) {
                    sendFailure(sendMsg1, sendMsg2, "orders do not match");
                    return;
                }
            }

            //check the amount of money matches
            if (sendOrder1.getSendingMoney() != sendOrder2.getReceivingMoney()) {
                sendFailure(sendMsg1, sendMsg2, "orders do not match");
                return;
            }

            if (sendOrder1.getReceivingMoney() != sendOrder2.getSendingMoney()) {
                sendFailure(sendMsg1, sendMsg2, "orders do not match");
                return;
            }

            //remove the books the agent sends
            ArrayList<BookInfo> books = agentInfo1.getBooks();
            ArrayList<BookInfo> removeBooks = sendOrder1.getSendingBooks();
            for (int i = 0; i < removeBooks.size(); i++) {
                for (int j = 0; j < books.size(); j++) {
                    if (books.get(j).getBookID() == removeBooks.get(i).getBookID()) {
                        books.remove(j);
                        break;
                    }
                }
            }

            //the same for the other agent
            books = agentInfo2.getBooks();
            removeBooks = sendOrder2.getSendingBooks();
            for (int i = 0; i < removeBooks.size(); i++) {
                for (int j = 0; j < books.size(); j++) {
                    if (books.get(j).getBookID() == removeBooks.get(i).getBookID()) {
                        books.remove(j);
                        break;
                    }
                }
            }

            //add books the agent receives
            books = agentInfo1.getBooks();
            ArrayList<BookInfo> addBooks = sendOrder2.getSendingBooks();
            for (int i = 0; i < addBooks.size(); i++) {
                books.add(addBooks.get(i));
            }

            //the same for the other one
            books = agentInfo2.getBooks();
            addBooks = sendOrder1.getSendingBooks();
            for (int i = 0; i < addBooks.size(); i++) {
                books.add(addBooks.get(i));
            }

            System.out.println("Transaction: " + sendOrder1);


            //transfer money between agents
            agentInfo1.setMoney(agentInfo1.getMoney()-sendOrder1.getSendingMoney());
            agentInfo1.setMoney(agentInfo1.getMoney()+sendOrder2.getSendingMoney());

            agentInfo2.setMoney(agentInfo2.getMoney()-sendOrder2.getSendingMoney());
            agentInfo2.setMoney(agentInfo2.getMoney()+sendOrder1.getSendingMoney());

            //send both agent an INFORM - the trade was successful
            ACLMessage reply1 = sendMsg1.createReply();
            ACLMessage reply2 = sendMsg2.createReply();
            reply1.setPerformative(ACLMessage.INFORM);
            reply2.setPerformative(ACLMessage.INFORM);
            reply1.setContent("done");
            reply2.setContent("done");
            send(reply1);
            send(reply2);

            System.out.println(agentName1 + " " + agentInfo1.toString());
            System.out.println(agentName2 + " " + agentInfo2.toString());

        }

        //send FAILURE to both agent, the failure is described as text, only performative is important
        void sendFailure(ACLMessage msg1, ACLMessage msg2, String text) {
            ACLMessage reply1 = msg1.createReply();
            ACLMessage reply2 = msg2.createReply();
            reply1.setPerformative(ACLMessage.FAILURE);
            reply2.setPerformative(ACLMessage.FAILURE);
            reply1.setContent(text);
            reply2.setContent(text);
            send(reply1);
            send(reply2);
        }


        //finds the books the agent wants to send but does not own
        ArrayList<BookInfo> getMissingBooks(AgentInfo agent, ArrayList<BookInfo> books) {
            ArrayList<BookInfo> missing = new ArrayList<BookInfo>();
            for (BookInfo bi : books) {
                boolean found = false;
                for (int i = 0; i < agent.getBooks().size(); i++) {
                    if (agent.getBooks().get(i).getBookID() == bi.getBookID()) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    missing.add(bi);
                }
            }
            return missing;
        }
    }

}
