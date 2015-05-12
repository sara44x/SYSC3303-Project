
import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

public class Server {	
	
	public static enum Opcode {RRQ, WRQ, ERROR};	// requests we can receive
	
	// valid modes that we can receive in RRQ or WRQ
	public static final String netascii = "NETASCII";
	public static final String octet = "OCTET";
	public static final String mail = "MAIL";
	
	DatagramPacket receivePacket;	// to receive datagram packets from Client
	DatagramSocket receiveSocket;	// Client sends to port 69

	public static void main(String[] args) {
		// create new thread to wait for and verify TFTP packets
		Server s = new Server();		
		s.listener();
	}
	
	public Server() {
		// create new socket to receive TFTP packets from Client
		try {
			receiveSocket = new DatagramSocket(69);
		} catch (SocketException se) {
			se.printStackTrace();
			System.exit(1);
		}   
	}
	
	// listens for new requests on port 69
	public void listener() {
		while (true) {			
			// prepare for receiving packet
			byte data[] = new byte[100];
			receivePacket = new DatagramPacket(data, data.length);
			System.out.println("Server: Waiting for Packet.\n");

			// block until a datagram packet is received from receiveSocket
			try {        
				System.out.println("Waiting...");
				receiveSocket.receive(receivePacket);
			} catch (IOException e) {
				System.out.print("IO Exception: likely:");
				System.out.println("Receive Socket Timed Out.\n" + e);
				e.printStackTrace();
				System.exit(1);
			}
			
			// to get rid of trailing null bytes from buffer	      
			byte received[] = new byte[receivePacket.getLength()];
			System.arraycopy(data, 0, received, 0, receivePacket.getLength());
			
			// check for valid TFTP request packet
			Opcode op;
			if (data[0] != 0) { // invalid Opcode
				System.out.println("Server: Invalid Opcode Received: \n");
				op = Opcode.ERROR;
				break;
			} else {
				switch (data[1]) {
					case 1: 		
						op = Opcode.RRQ;
						break;
					case 2:
						op = Opcode.WRQ;
						break;
					default: // invalid request						
						op = Opcode.ERROR;
						break;
				}
			}
			
			// get filename length, and parse the filename
			int fLen = 2; // filename length counter
			if (op != Opcode.ERROR) {
				for (int i=2; i<received.length; i++) {
					if (received[i] == 0) {
						break;
					} else {
						fLen += 1;	// haven't found 0 yet, so keep increasing size of filename
					}
				}	
				// didn't find a 0 byte, filename is improperly formatted
				if (fLen == received.length) op = Opcode.ERROR;
			}
			
			// get mode length, and parse mode
			int mLen = 2+fLen+1; // mode length counter
			if (op != Opcode.ERROR) {
				for (int i=2+fLen+1; i<received.length; i++) {
					if (received[i] == 0) {
						break;
					} else {
						mLen += 1;	// haven't found 0 yet, so keep increasing size of mode
					}
				}	
				// didn't find a 0 byte, mode is improperly formatted
				if (mLen == received.length) op = Opcode.ERROR;
			}
			
			// check if there is other stuff at end of packet
			if (mLen != received.length-1) {
				op = Opcode.ERROR;  
			}
			
			// parse request for filename and mode Strings
			String fileName = ""; 
			String mode = "";
			if (op != Opcode.ERROR) {				
				// convert filename in byte array to a String
				fileName = new String(receivePacket.getData(), 2, fLen-2, Charset.forName("utf-8"));
				
				// convert mode in byte array to a String
				mode = new String(receivePacket.getData(), fLen+1, mLen-(fLen+1), Charset.forName("utf-8"));
				
				// check if mode is one of the three valid types (NETASCII, OCTET, or MAIL), ignoring case
				if (!(mode.equalsIgnoreCase(netascii) || mode.equalsIgnoreCase(octet) || 
						mode.equalsIgnoreCase(mail))) {
					op = Opcode.ERROR;
				}
			}			
			
			// deal with OPcodes
			if (op != Opcode.ERROR) {				
				// tell user if Read or Write request was received
				if (op == Opcode.RRQ) {
					System.out.println("Server: Read Request Received:");
				} else if (op == Opcode.WRQ) {
					System.out.println("Server: Write Request Received:");
				}				
				
				// process the received datagram and print data
				System.out.println("From host: " + receivePacket.getAddress() + " : " + receivePacket.getPort());
				System.out.print("Containing " + receivePacket.getLength() + " bytes: \n");
				System.out.println(Arrays.toString(received));
				System.out.print("\tFilename: " + fileName + "\t\tMode: " + mode + "\n");
				
				// create new thread to communicate with Client and transfer file
				// pass it datagram that was received				
				Thread clientConnectionThread = new Thread(
						new ClientConnection(receivePacket, op, fileName), "Client Connection Thread");
				System.out.println("Server: Packet Sent for Processing: \n");
				clientConnectionThread.start();					
			} else {
				System.out.println("Server: Invalid Request Received: \n");
			}
		} // end of while
		receiveSocket.close();
	}
}

class ClientConnection implements Runnable {
	Server.Opcode op;	// opcode from Client request
	String fileName;	// filename from the Client request packet
	
	public static final int MAX_DATA = 512;	//maximum number of bytes in data block
	
	DatagramPacket receivePacket;		// request received on port69 from Client
	DatagramPacket sendPacket;			// datagram packet to send in response to the Client
	DatagramPacket transferPacket;		// datagram packet received from Client during file transfer
	DatagramSocket sendReceiveSocket;	// new socket connection with Client for file transfer

	public ClientConnection(DatagramPacket receivePacket, Server.Opcode opServer, String fn) {
		// pass in the received datagram packet from the Server
		// in order to facilitate file transfers with the Client
		this.receivePacket = receivePacket;
		this.op = opServer;
		this.fileName = fn;
	}
	
	public void run() {
		// to get rid of trailing null bytes from buffer	      
		byte received[] = new byte[receivePacket.getLength()];
		System.arraycopy(receivePacket.getData(), 0, received, 0, receivePacket.getLength());	
		
		// open new socket to send response to Client
		try {
			sendReceiveSocket = new DatagramSocket();
		} catch (SocketException se) {
			se.printStackTrace();
			System.exit(1);
		}
		
		// create response packet
		byte response[] = new byte[4];		// opcode and block number for initial response
		byte blockNum = 0;					// data block number
		boolean transferFinished = false;	// keep track of data transfered
		if (op == Server.Opcode.RRQ) {			// read request response			
			response[0] = 0;
			response[1] = 3;	// DATA opcode
			response[2] = 0;
			response[3] = (byte) (blockNum + 1);	// start at block number 1
			
			InputStream in = newInputStream(fileName);
			
		} else if (op == Server.Opcode.WRQ) {	// write request response
			response[0] = 0;
			response[1] = 4;	// ACK opcode
			response[2] = 0;
			response[3] = blockNum;	// ACK WRQ initial block number
			
			// Send the initial ACK packet to the client via the send socket.
			sendPacket = new DatagramPacket(response, 4, receivePacket.getAddress(), receivePacket.getPort());
			try {
				sendReceiveSocket.send(sendPacket);
				// print out thread and port info, from which the packet was sent to Client
				System.out.println(Thread.currentThread() + ": ACK packet sent using port " + 
						sendReceiveSocket.getLocalPort() + "\n");
				// print byte info on packet being sent to Client
				System.out.print("Containing " + sendPacket.getLength() + " bytes: \n");
				System.out.println(Arrays.toString(response));
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(1);
			}
			
			// receive write data from Client and facilitate transfer
			while (!transferFinished) { 
				// prepare for receiving packet with write data
				byte transfer[] = new byte[MAX_DATA + 4];
				transferPacket = new DatagramPacket(transfer, transfer.length);
				System.out.println(Thread.currentThread() + ": Waiting for write data.\n");

				// block until a datagram packet is received from receiveSocket
				try {        
					System.out.println("Waiting...");
					sendReceiveSocket.receive(transferPacket);
				} catch (IOException e) {
					System.out.print("IO Exception: likely:");
					System.out.println("Receive Socket Timed Out.\n" + e);
					e.printStackTrace();
					System.exit(1);
				}
				
				blockNum = transfer[3];	// get the data block number from Client write packet
				response[3] = blockNum;	// set the ACK block number
				
				// to get just the data portion of the transerPacket	      
				byte data[] = new byte[transferPacket.getLength() - 4];
				System.arraycopy(transfer, 4, data, 0, transferPacket.getLength() - 4);
				
				// write the data from the transfer to a file at the requested filename
				try {
					Files.write(Paths.get(fileName), data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
					System.out.println(Thread.currentThread() + ": writing data to file: " + fileName);
				} catch (IOException e) {
					System.out.println(Thread.currentThread() + ": writing data to file has failed.\n");
					e.printStackTrace();
				}
				
				// send ACK packet to the client via the send socket
				sendPacket = new DatagramPacket(response, 4, transferPacket.getAddress(), transferPacket.getPort());
				try {
					sendReceiveSocket.send(sendPacket);
					// print out thread and port info, from which the packet was sent to Client
					System.out.println(Thread.currentThread() + ": ACK packet sent using port " + 
							sendReceiveSocket.getLocalPort() + "\n");
					// print byte info on packet being sent to Client
					System.out.print("Containing " + sendPacket.getLength() + " bytes: \n");
					System.out.println(Arrays.toString(response));
				} catch (IOException e) {
					e.printStackTrace();
					System.exit(1);
				}
				
				// check if tranfer is over, and that was last ACK to send
				if (data.length < MAX_DATA) {
					transferFinished = true;	// transfer is complete
				}
			}
		} else {
			try {
				throw new Exception("Not yet implemented");
			} catch (Exception e) {
				e.printStackTrace();
			}
		}	
				
		// we are finished file transfer, close socket
		sendReceiveSocket.close(); 
	}	
}
