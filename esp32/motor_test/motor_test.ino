uint8_t pinPWML = A4; 
int pinLIN1 = 25; 
int pinLIN2 = 26; 

uint8_t pinPWMR = A5;
int pinRIN1 = 27; 
int pinRIN2 = 14; 

//33, 25, 26, 27, 14, 12, 13 are good

void setup()
{
  pinMode(pinPWML, OUTPUT);
  pinMode(pinLIN1, OUTPUT);
  pinMode(pinLIN2, OUTPUT);
  ledcAttachPin(pinPWML, 1); 
  ledcSetup(1, 12000, 8);
  pinMode(pinPWMR, OUTPUT);
  pinMode(pinRIN1, OUTPUT);
  pinMode(pinRIN2, OUTPUT);
  ledcAttachPin(pinPWMR, 1); 
  ledcSetup(2, 12000, 8);
}

void loop()
{
  ledcWrite(1, 255);
  ledcWrite(2, 255);
  delay(200);
  digitalWrite(pinLIN1, 1);
  digitalWrite(pinLIN2, 0);
  digitalWrite(pinRIN1, 0);
  digitalWrite(pinRIN2, 1);
  delay(200);
  digitalWrite(pinLIN1, 0);
  digitalWrite(pinLIN2, 1);
  digitalWrite(pinRIN1, 1);
  digitalWrite(pinRIN2, 0);
}
