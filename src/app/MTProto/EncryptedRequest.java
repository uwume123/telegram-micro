package mtproto;

import java.io.IOException;

import crypto.SHA256;
import crypto.AES256IGE;

import support.Encode;
import support.ByteArrayPlus;
import support.ArrayPlus;
import support.RandomPlus;
import crypto.SecureRandomPlus;

public class EncryptedRequest {
  byte[] unencrypted_data;
  //https://core.telegram.org/mtproto/description#defining-aes-key-and-initialization-vector
  public EncryptedRequest(byte[] unencrypted_data) {
    this.unencrypted_data = unencrypted_data;
  }

  private long message_id() {
    //https://core.telegram.org/mtproto/description#message-identifier-msg-id
    return (System.currentTimeMillis()/1000L)<<32;
  }

  public static byte[] pad_unencrypted_data(byte[] data_to_pad, RandomPlus random_number_generator) {
    return (new ByteArrayPlus())
      .append_raw_bytes(data_to_pad)
      .pad_to_length(12, random_number_generator) //this padding has to be between 12-1024, so let's just pad 12 for now, we should later randomize this in both length and content
      .pad_to_alignment(16, random_number_generator) //this should also have its content randomized
      .toByteArray(); //the resulting message length should be divisible by 16, the question is what counts as the message
  }
  
  public void send(MTProtoConnection sender) {
    SecureRandomPlus random_number_generator = new SecureRandomPlus();
    byte[] padded_unencrypted_data = pad_unencrypted_data(unencrypted_data, random_number_generator);
    //msg_key_large = SHA256 (substr (auth_key, 88+x, 32) + plaintext + random_padding);
    byte[] msg_key_large = (new SHA256()).digest(
      (new ByteArrayPlus())
        .append_raw_bytes_from_up_to(sender.auth_key, 88, 32) //88 from client to server, 88+8 from server to client
        .append_raw_bytes(padded_unencrypted_data)
        .toByteArray()
    ); //Apparently we should only be getting the middle 128 bits of this???
    System.out.println("Message key generated");
    
    byte[] msg_key = ArrayPlus.subarray(msg_key_large, 8, 16);
    System.out.println("Message key length (should be 16)");
    System.out.println(msg_key.length);

    byte[] sha256_a = (new SHA256()).digest(
      (new ByteArrayPlus())
        .append_raw_bytes(msg_key)
        .append_raw_bytes_up_to(sender.auth_key, 36)
        .toByteArray()
    );
    byte[] sha256_b = (new SHA256()).digest(
      (new ByteArrayPlus())
        .append_raw_bytes_from_up_to(sender.auth_key, 40, 36)
        .append_raw_bytes(msg_key)
        .toByteArray()
    );
    
    //aes_key = substr (sha256_a, 0, 8) + substr (sha256_b, 8, 16) + substr (sha256_a, 24, 8);
    byte[] aes_key = (new ByteArrayPlus())
      .append_raw_bytes_up_to(sha256_a, 8)
      .append_raw_bytes_from_up_to(sha256_b, 8, 16)
      .append_raw_bytes_from_up_to(sha256_a, 24, 8)
      .toByteArray();
    
    //aes_iv = substr (sha256_b, 0, 8) + substr (sha256_a, 8, 16) + substr (sha256_b, 24, 8);
    byte[] aes_iv = (new ByteArrayPlus())
      .append_raw_bytes_up_to(sha256_b, 8)
      .append_raw_bytes_from_up_to(sha256_a, 8, 16)
      .append_raw_bytes_from_up_to(sha256_b, 24, 8)
      .toByteArray();
    
    byte[] message_data = AES256IGE.encrypt(aes_key, aes_iv, padded_unencrypted_data);
    //https://core.telegram.org/mtproto/description#encrypted-message-encrypted-data
    //I'm pretty sure encrypted_data is what needs to be encrypted not padded_unencrypted_data
    //this encrypted_data should also be used when generating the message_key
    //instead of just the plain padded unencrypted data
    //according to https://core.telegram.org/mtproto/description#protocol-description
    byte[] encrypted_data = (new ByteArrayPlus())
      .append_long(sender.server_salt) //the salt needs to be encoded as an int64... does that mean it's little endian too?
      .append_long(sender.session_id)
      .append_long(message_id())
      .append_int(sender.seq_no)
      .append_int(message_data.length)
      .append_raw_bytes(message_data)
      .pad_to_length(12) //this padding has to be between 12-1024, so let's just pad 12 for now, we should later randomize this in both length and content
      .pad_to_alignment(16) //this should also have its content randomized, also I'm not actually sure if this has to be padded to alignment
      .toByteArray();
    
    //https://core.telegram.org/mtproto/description#encrypted-message
    byte[] encrypted_message = (new ByteArrayPlus())
      .append_long(sender.auth_key_id)
      .append_raw_bytes(msg_key)
      .append_raw_bytes(encrypted_data)
      .toByteArray();
    
    sender.seq_no += 1; //this might be a race condition idk lol
    
    (new TCPRequest(encrypted_message)).send(sender);
  }
}
